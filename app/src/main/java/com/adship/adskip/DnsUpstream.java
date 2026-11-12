package com.adship.adskip;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Resolves one raw DNS query through UDP, DoT or DoH.
 *
 * <p>DoH/DoT hostnames are bootstrapped with a direct UDP query to well-known resolvers. This is
 * essential: while the tunnel is up, the system resolver points at this app, so using the system
 * resolver to find the DoH hostname would recurse forever.</p>
 */
public final class DnsUpstream {

    private static final String TAG = "AdSkip";
    private static final int MAX_DNS_MESSAGE = 65535;
    private static final String[] BOOTSTRAP_SERVERS = {"223.5.5.5", "1.1.1.1", "8.8.8.8"};
    private static final long BOOTSTRAP_TTL_MS = 10 * 60 * 1000L;

    public interface Protector {
        boolean protect(Socket socket);

        boolean protect(DatagramSocket socket);
    }

    private final Protector protector;
    private final int timeoutMs;
    private final SSLSocketFactory sslFactory;
    private final ConcurrentHashMap<String, CacheEntry> dnsCache = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public DnsUpstream(Protector protector, int timeoutMs) {
        this.protector = protector;
        this.timeoutMs = Math.max(1500, timeoutMs);
        this.sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    public byte[] resolve(DnsConfig config, byte[] query) {
        if (config == null || query == null || query.length < 12) {
            return null;
        }
        try {
            switch (config.mode) {
                case DOH:
                    return resolveDoh(config.address, query);
                case DOT:
                    return resolveDot(config.address, query);
                case UDP:
                default:
                    return resolveUdp(config.address, query);
            }
        } catch (Exception e) {
            Log.w(TAG, "Upstream " + config.mode + " failed: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- UDP

    private byte[] resolveUdp(String address, byte[] query) throws IOException {
        DnsConfig.HostPort hostPort = DnsConfig.HostPort.parse(address, 53);
        if (hostPort == null) {
            throw new IOException("Bad UDP endpoint");
        }
        InetAddress server = InetAddress.getByName(hostPort.host);
        try (DatagramSocket socket = new DatagramSocket()) {
            protector.protect(socket);
            socket.connect(server, hostPort.port);
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(query, query.length, server, hostPort.port));
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) {
                    throw new SocketTimeoutException("UDP DNS timeout");
                }
                socket.setSoTimeout(remaining);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 12 || DnsCodec.u16(buffer, 0) != DnsCodec.u16(query, 0)) {
                    continue;
                }
                byte[] response = Arrays.copyOf(buffer, packet.getLength());
                if ((DnsCodec.u16(response, 2) & 0x0200) != 0) {
                    byte[] tcp = resolveTcp(server, hostPort.port, query);
                    if (tcp != null) {
                        return tcp;
                    }
                }
                return response;
            }
        }
    }

    private byte[] resolveTcp(InetAddress server, int port, byte[] query) throws IOException {
        try (Socket socket = new Socket()) {
            protector.protect(socket);
            socket.connect(new InetSocketAddress(server, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readUnsignedShort();
            if (length < 12) {
                return null;
            }
            byte[] response = new byte[length];
            in.readFully(response);
            return response;
        }
    }

    // ---------------------------------------------------------------- DoT

    private byte[] resolveDot(String address, byte[] query) throws IOException {
        DnsConfig.HostPort hostPort = DnsConfig.HostPort.parse(address, 853);
        if (hostPort == null) {
            throw new IOException("Bad DoT endpoint");
        }
        IOException last = null;
        for (InetAddress server : resolveHost(hostPort.host)) {
            try (SSLSocket socket = openTls(server, hostPort.host, hostPort.port)) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeShort(query.length);
                out.write(query);
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = in.readUnsignedShort();
                if (length < 12) {
                    throw new IOException("Short DoT response");
                }
                byte[] response = new byte[length];
                in.readFully(response);
                return response;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new UnknownHostException(hostPort.host);
    }

    // ---------------------------------------------------------------- DoH

    private byte[] resolveDoh(String address, byte[] query) throws IOException {
        URI uri;
        try {
            uri = new URI(address);
        } catch (Exception e) {
            throw new IOException("Bad DoH URL", e);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("DoH URL has no host");
        }
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }

        IOException last = null;
        for (InetAddress server : resolveHost(host)) {
            try (SSLSocket socket = openTls(server, host, port)) {
                StringBuilder head = new StringBuilder(256);
                head.append("POST ").append(path).append(" HTTP/1.1\r\n");
                head.append("Host: ").append(host);
                if (port != 443) {
                    head.append(':').append(port);
                }
                head.append("\r\n");
                head.append("Accept: application/dns-message\r\n");
                head.append("Content-Type: application/dns-message\r\n");
                head.append("Content-Length: ").append(query.length).append("\r\n");
                head.append("User-Agent: AdSkip/1.0\r\n");
                head.append("Connection: close\r\n\r\n");
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(query);
                out.flush();

                HttpResponse response = readHttpResponse(socket.getInputStream());
                if (response.status != 200) {
                    throw new IOException("DoH HTTP " + response.status);
                }
                if (response.body.length < 12) {
                    throw new IOException("Short DoH response");
                }
                return response.body;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new UnknownHostException(host);
    }

    private HttpResponse readHttpResponse(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 8192);
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("Empty HTTP response");
        }
        String[] statusParts = statusLine.split(" ");
        if (statusParts.length < 2) {
            throw new IOException("Bad HTTP status line");
        }
        int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad HTTP status code", e);
        }

        int contentLength = -1;
        boolean chunked = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("content-length".equalsIgnoreCase(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
            } else if ("transfer-encoding".equalsIgnoreCase(name)
                    && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }

        byte[] body;
        if (chunked) {
            body = readChunked(in);
        } else if (contentLength >= 0) {
            if (contentLength > MAX_DNS_MESSAGE) {
                throw new IOException("HTTP body too large");
            }
            body = new byte[contentLength];
            readFully(in, body);
        } else {
            body = readToEnd(in);
        }
        return new HttpResponse(status, body);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        while (true) {
            String line = readLine(in);
            if (line == null) {
                break;
            }
            int semicolon = line.indexOf(';');
            String hex = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk size", e);
            }
            if (size < 0 || out.size() + size > MAX_DNS_MESSAGE) {
                throw new IOException("Chunked body too large");
            }
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // Discard trailers.
                }
                break;
            }
            byte[] chunk = new byte[size];
            readFully(in, chunk);
            out.write(chunk, 0, chunk.length);
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_DNS_MESSAGE) {
                throw new IOException("HTTP body too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 8192) {
                throw new IOException("HTTP header line too long");
            }
        }
        if (b == -1 && line.size() == 0) {
            return null;
        }
        return line.toString("ISO-8859-1");
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int offset = 0;
        while (offset < out.length) {
            int read = in.read(out, offset, out.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream");
            }
            offset += read;
        }
    }

    private static final class HttpResponse {
        final int status;
        final byte[] body;

        HttpResponse(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    // ---------------------------------------------------------------- TLS

    private SSLSocket openTls(InetAddress server, String host, int port) throws IOException {
        Socket raw = new Socket();
        boolean success = false;
        try {
            protector.protect(raw);
            raw.connect(new InetSocketAddress(server, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            SSLSocket socket = (SSLSocket) sslFactory.createSocket(raw, host, port, true);
            SSLParameters parameters = socket.getSSLParameters();
            if (!DnsConfig.isIpLiteral(host)) {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            }
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            success = true;
            return socket;
        } finally {
            if (!success) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    // ---------------------------------------------------------------- bootstrap

    private List<InetAddress> resolveHost(String host) throws IOException {
        if (DnsConfig.isIpLiteral(host)) {
            return Collections.singletonList(InetAddress.getByName(host));
        }
        CacheEntry cached = dnsCache.get(host);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return cached.addresses;
        }

        IOException last = null;
        for (String server : BOOTSTRAP_SERVERS) {
            try {
                List<InetAddress> addresses = queryA(server, host);
                if (!addresses.isEmpty()) {
                    if (dnsCache.size() > 64) {
                        dnsCache.clear();
                    }
                    dnsCache.put(host, new CacheEntry(addresses, now + BOOTSTRAP_TTL_MS));
                    return addresses;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new UnknownHostException(host);
    }

    private List<InetAddress> queryA(String server, String host) throws IOException {
        byte[] query = buildAQuery(host);
        InetAddress address = InetAddress.getByName(server);
        byte[] response;
        try (DatagramSocket socket = new DatagramSocket()) {
            protector.protect(socket);
            socket.connect(address, 53);
            socket.setSoTimeout(Math.min(timeoutMs, 2500));
            socket.send(new DatagramPacket(query, query.length, address, 53));
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            response = Arrays.copyOf(buffer, packet.getLength());
        }
        if (response.length < 12 || DnsCodec.u16(response, 0) != DnsCodec.u16(query, 0)) {
            return Collections.emptyList();
        }
        return parseAAnswers(response);
    }

    private byte[] buildAQuery(String host) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(random.nextInt(0x10000));
        out.writeShort(0x0100);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        String[] labels = host.split("\\.");
        for (String label : labels) {
            byte[] data = label.getBytes(StandardCharsets.US_ASCII);
            if (data.length == 0 || data.length > 63) {
                throw new IOException("Bad hostname label");
            }
            out.writeByte(data.length);
            out.write(data);
        }
        out.writeByte(0);
        out.writeShort(1);
        out.writeShort(1);
        out.flush();
        return bytes.toByteArray();
    }

    private List<InetAddress> parseAAnswers(byte[] message) {
        int questions = DnsCodec.u16(message, 4);
        int answers = DnsCodec.u16(message, 6);
        int pos = 12;
        try {
            for (int i = 0; i < questions; i++) {
                pos = skipName(message, pos);
                pos += 4;
            }
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        List<InetAddress> result = new ArrayList<>(2);
        for (int i = 0; i < answers; i++) {
            try {
                pos = skipName(message, pos);
                if (pos + 10 > message.length) {
                    break;
                }
                int type = DnsCodec.u16(message, pos);
                int length = DnsCodec.u16(message, pos + 8);
                pos += 10;
                if (pos + length > message.length) {
                    break;
                }
                if (type == DnsCodec.TYPE_A && length == 4) {
                    byte[] ip = Arrays.copyOfRange(message, pos, pos + 4);
                    result.add(InetAddress.getByAddress(ip));
                }
                pos += length;
            } catch (Exception e) {
                break;
            }
        }
        return result;
    }

    private static int skipName(byte[] message, int start) {
        int pos = start;
        while (true) {
            if (pos >= message.length) {
                throw new IllegalArgumentException("Truncated name");
            }
            int length = message[pos] & 0xFF;
            if (length == 0) {
                return pos + 1;
            }
            if ((length & 0xC0) == 0xC0) {
                if (pos + 1 >= message.length) {
                    throw new IllegalArgumentException("Truncated pointer");
                }
                return pos + 2;
            }
            if ((length & 0xC0) != 0) {
                throw new IllegalArgumentException("Bad label");
            }
            pos += 1 + length;
        }
    }

    private static final class CacheEntry {
        final List<InetAddress> addresses;
        final long expiresAt;

        CacheEntry(List<InetAddress> addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }
}
