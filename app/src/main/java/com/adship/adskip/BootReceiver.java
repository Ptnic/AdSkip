package com.adship.adskip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AdSkip";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!DnsConfig.load(context).startOnBoot) {
            return;
        }
        try {
            if (VpnService.prepare(context) != null) {
                // VPN consent was revoked or never granted; only the UI can request it.
                return;
            }
            Intent service = new Intent(context, DnsVpnService.class)
                    .setAction(DnsVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Boot start failed: " + e.getMessage());
        }
    }
}
