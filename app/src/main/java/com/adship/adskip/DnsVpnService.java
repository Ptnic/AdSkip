package com.adship.adskip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A split-tunnel VpnService that only owns one virtual IP: 10.111.222.2.
 *
 * <p>Android sends the VPN's DNS queries there. We read raw IPv4/UDP packets from the TUN,
 * filter the question name against a local blocklist, forward allowed questions to the selected
 * upstream, and write the DNS answer back. All non-DNS traffic bypasses the tunnel entirely,
 * which is why this is lightweight and does not need root.</p>
 */
public final class DnsVpnService extends VpnService {

    public static final String ACTION_START = "com.adship.adskip.action.START";
    public static final String ACTION_STOP = "com.adship.adskip.action.STOP";

    private static final String TAG = "AdSkip";
    private static final String CHANNEL_ID = "adskip_vpn";
    private static final int NOTIFICATION_ID = 1001;
    private static final String VIRTUAL_DNS = "10.111.222.2";
    private static final int MAX_DNS_UDP = 1400;

    private static volatile boolean running;
    private static volatile boolean starting;
    private static volatile int ruleCount;
    private static volatile DnsConfig activeConfig;

    private final Object writeLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean startRequested;
    private volatile ParcelFileDescriptor tun;
    private volatile FileInputStream tunIn;
    private volatile FileOutputStream tunOut;
    private volatile Thread readerThread;
    private volatile ExecutorService workers;
    private volatile DnsConfig config;
    private volatile Blocklist blocklist;
    private volatile DnsUpstream upstream;
    private volatile AppUidResolver appUidResolver;

    private final DnsUpstream.Protector protector = new DnsUpstream.Protector() {
        @Override
        public boolean protect(Socket socket) {
            return DnsVpnService.this.protect(socket);
        }

        @Override
        public boolean protect(DatagramSocket socket) {
            return DnsVpnService.this.protect(socket);
        }
    };

    private final Runnable notificationTick = new Runnable() {
        @Override
        public void run() {
            if (running) {
                updateNotification(getString(R.string.notif_text, FilterStats.blocked()));
                mainHandler.postDelayed(this, 30_000L);
            }
        }
    };

    public static boolean isRunning() {
        return running;
    }

    public static boolean isStarting() {
        return starting;
    }

    public static int getRuleCount() {
        return ruleCount;
    }

    public static DnsConfig getActiveConfig() {
        return activeConfig;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            startRequested = false;
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running || starting) {
            return START_NOT_STICKY;
        }

        startRequested = true;
        starting = true;
        try {
            goForeground(buildNotification(getString(R.string.notif_text_starting)));
        } catch (RuntimeException e) {
            starting = false;
            startRequested = false;
            saveError(e.getMessage() == null ? "foreground service" : e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(this::startInternal, "vpn-setup").start();
        return START_NOT_STICKY;
    }

    private void startInternal() {
        try {
            if (!startRequested) {
                return;
            }
            if (VpnService.prepare(this) != null) {
                throw new IllegalStateException(getString(R.string.error_not_prepared));
            }
            DnsConfig newConfig = DnsConfig.load(this);
            if (!newConfig.isValid()) {
                throw new IllegalArgumentException(getString(R.string.error_no_config));
            }
            Blocklist newBlocklist = Blocklist.load(this);
            if (!startRequested) {
                return;
            }

            Builder builder = new Builder();
            builder.setSession(getString(R.string.app_name));
            builder.addAddress("10.111.222.1", 24);
            builder.addDnsServer(VIRTUAL_DNS);
            builder.addRoute(VIRTUAL_DNS, 32);
            builder.allowFamily(OsConstants.AF_INET);
            builder.setMtu(1500);
            builder.setBlocking(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            ParcelFileDescriptor newTun = builder.establish();
            if (newTun == null) {
                throw new IOException(getString(R.string.error_establish));
            }
            synchronized (lifecycleLock) {
                if (!startRequested) {
                    newTun.close();
                    return;
                }

                config = newConfig;
                blocklist = newBlocklist;
                upstream = new DnsUpstream(protector, 5000);
                appUidResolver = new AppUidResolver(this);
                tun = newTun;
                tunIn = new FileInputStream(newTun.getFileDescriptor());
                tunOut = new FileOutputStream(newTun.getFileDescriptor());
                workers = Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "dns-worker");
                    thread.setDaemon(true);
                    return thread;
                });

                FilterStats.reset();
                ruleCount = newBlocklist.size();
                activeConfig = newConfig;
                running = true;
                starting = false;
                clearError();

                Thread reader = new Thread(this::readLoop, "dns-reader");
                reader.setDaemon(true);
                readerThread = reader;
                reader.start();
            }

            mainHandler.post(() -> {
                updateNotification(getString(R.string.notif_text, FilterStats.blocked()));
                mainHandler.postDelayed(notificationTick, 30_000L);
            });
        } catch (Throwable t) {
            if (!startRequested) {
                return;
            }
            Log.e(TAG, "Could not start VPN", t);
            fail(t.getMessage());
        }
    }

    private void fail(String message) {
        synchronized (lifecycleLock) {
            startRequested = false;
            running = false;
            starting = false;
            activeConfig = null;
        }
        saveError(message == null ? "unknown" : message);
        mainHandler.post(() -> {
            shutdown();
            stopSelf();
        });
    }

    private void readLoop() {
        byte[] buffer = new byte[32767];
        while (running) {
            FileInputStream input = tunIn;
            if (input == null) {
                break;
            }
            int length;
            try {
                length = input.read(buffer);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "TUN read ended: " + e.getMessage());
                }
                break;
            }
            if (length <= 0) {
                continue;
            }
            byte[] packet = Arrays.copyOf(buffer, length);
            ExecutorService executor = workers;
            if (executor == null) {
                break;
            }
            try {
                executor.execute(() -> handlePacket(packet));
            } catch (RejectedExecutionException e) {
                break;
            }
        }
    }

    private void handlePacket(byte[] packet) {
        try {
            UdpPacket parsed = UdpPacket.parse(packet);
            if (parsed == null) {
                return;
            }
            DnsCodec.Question question = DnsCodec.parseQuestion(parsed.payload);
            String domain = question == null ? null : question.name;

            Blocklist currentBlocklist = blocklist;
            DnsConfig currentConfig = config;
            boolean blocked = currentConfig != null && currentConfig.blockAds
                    && question != null && currentBlocklist != null
                    && currentBlocklist.isBlocked(domain);
            AppUidResolver resolver = appUidResolver;
            String appName = resolver == null
                    ? "未知应用"
                    : resolver.resolve(parsed.sourceIp, parsed.sourcePort).label;
            FilterStats.onQuery(domain, blocked,
                    question == null ? 0 : question.type, appName);

            byte[] response;
            if (blocked) {
                response = DnsCodec.buildSinkhole(parsed.payload);
            } else {
                DnsUpstream currentUpstream = upstream;
                response = currentUpstream == null || currentConfig == null
                        ? null
                        : currentUpstream.resolve(currentConfig, parsed.payload);
                if (response == null) {
                    response = DnsCodec.buildFailure(parsed.payload, 2);
                }
            }
            if (response == null) {
                return;
            }
            if (response.length > MAX_DNS_UDP) {
                response = DnsCodec.buildTruncated(parsed.payload);
            }
            byte[] out = parsed.buildResponse(response);
            if (out == null) {
                return;
            }
            FileOutputStream output = tunOut;
            if (output == null) {
                return;
            }
            synchronized (writeLock) {
                output.write(out);
                output.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "DNS packet failed: " + e.getMessage());
        }
    }

    private void shutdown() {
        synchronized (lifecycleLock) {
            startRequested = false;
            running = false;
            starting = false;
            activeConfig = null;
            mainHandler.removeCallbacks(notificationTick);

            FileInputStream input = tunIn;
            tunIn = null;
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Expected while unblocking the reader.
                }
            }
            FileOutputStream output = tunOut;
            tunOut = null;
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
            ParcelFileDescriptor descriptor = tun;
            tun = null;
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
            ExecutorService executor = workers;
            workers = null;
            if (executor != null) {
                executor.shutdownNow();
            }
            Thread reader = readerThread;
            readerThread = null;
            if (reader != null && reader != Thread.currentThread()) {
                reader.interrupt();
            }
            blocklist = null;
            upstream = null;
            appUidResolver = null;
            config = null;
            ruleCount = 0;
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (RuntimeException ignored) {
                // Service may already be stopping.
            }
        }
    }

    @Override
    public void onRevoke() {
        shutdown();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    private void goForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(activityPendingIntent())
                .addAction(R.drawable.ic_notification, getString(R.string.notif_stop),
                        stopPendingIntent());
        builder.setColor(getResources().getColor(R.color.accent));
        builder.setCategory(Notification.CATEGORY_SERVICE);
        return builder.build();
    }

    private PendingIntent activityPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private PendingIntent stopPendingIntent() {
        Intent intent = new Intent(this, DnsVpnService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 1, intent, flags);
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void saveError(String message) {
        getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putString(DnsConfig.KEY_LAST_ERROR, message)
                .apply();
    }

    private void clearError() {
        getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .edit()
                .remove(DnsConfig.KEY_LAST_ERROR)
                .apply();
    }

    // ------------------------------------------------------------- packet codec

    private static final class UdpPacket {
        final byte[] packet;
        final int ihl;
        final int sourcePort;
        final byte[] sourceIp;
        final byte[] payload;

        private UdpPacket(byte[] packet, int ihl, int sourcePort, byte[] sourceIp,
                          byte[] payload) {
            this.packet = packet;
            this.ihl = ihl;
            this.sourcePort = sourcePort;
            this.sourceIp = sourceIp;
            this.payload = payload;
        }

        static UdpPacket parse(byte[] packet) {
            if (packet == null || packet.length < 20) {
                return null;
            }
            int version = (packet[0] >> 4) & 0x0F;
            if (version != 4) {
                return null;
            }
            int ihl = (packet[0] & 0x0F) * 4;
            if (ihl < 20 || packet.length < ihl + 8) {
                return null;
            }
            int totalLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
            if (totalLength < ihl + 8 || totalLength > packet.length) {
                return null;
            }
            int flagsAndFragment = ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF);
            if ((flagsAndFragment & 0x3FFF) != 0) {
                // More-fragments or a non-zero fragment offset: not a complete DNS datagram.
                return null;
            }
            if ((packet[9] & 0xFF) != 17) {
                return null;
            }
            if (packet[16] != (byte) 10 || packet[17] != (byte) 111
                    || packet[18] != (byte) 222 || packet[19] != (byte) 2) {
                return null;
            }
            int sourcePort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
            int destinationPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            if (destinationPort != 53) {
                return null;
            }
            int udpLength = ((packet[ihl + 4] & 0xFF) << 8) | (packet[ihl + 5] & 0xFF);
            if (udpLength < 8 || ihl + udpLength > totalLength) {
                return null;
            }
            byte[] payload = Arrays.copyOfRange(packet, ihl + 8, ihl + udpLength);
            byte[] sourceIp = Arrays.copyOfRange(packet, 12, 16);
            return new UdpPacket(packet, ihl, sourcePort, sourceIp, payload);
        }

        byte[] buildResponse(byte[] payload) {
            int totalLength = ihl + 8 + payload.length;
            if (totalLength > 1500) {
                return null;
            }
            byte[] out = new byte[totalLength];
            System.arraycopy(packet, 0, out, 0, ihl);

            out[2] = (byte) (totalLength >> 8);
            out[3] = (byte) totalLength;
            out[6] = 0;
            out[7] = 0;
            out[8] = 64;
            out[9] = 17;
            System.arraycopy(packet, 16, out, 12, 4);
            System.arraycopy(packet, 12, out, 16, 4);
            out[10] = 0;
            out[11] = 0;
            int ipChecksum = checksum(out, 0, ihl);
            out[10] = (byte) (ipChecksum >> 8);
            out[11] = (byte) ipChecksum;

            int udpOffset = ihl;
            out[udpOffset] = 0;
            out[udpOffset + 1] = 53;
            out[udpOffset + 2] = (byte) (sourcePort >> 8);
            out[udpOffset + 3] = (byte) sourcePort;
            int udpLength = 8 + payload.length;
            out[udpOffset + 4] = (byte) (udpLength >> 8);
            out[udpOffset + 5] = (byte) udpLength;
            out[udpOffset + 6] = 0;
            out[udpOffset + 7] = 0;
            System.arraycopy(payload, 0, out, udpOffset + 8, payload.length);
            return out;
        }
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int index = offset;
        int end = offset + length;
        while (index + 1 < end) {
            sum += ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
            index += 2;
        }
        if (index < end) {
            sum += (data[index] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }
}
