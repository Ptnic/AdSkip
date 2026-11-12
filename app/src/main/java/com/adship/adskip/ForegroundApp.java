package com.adship.adskip;

import android.os.SystemClock;

/**
 * Last foreground package observed by the optional accessibility service. This is only a
 * fallback for DNS attribution on devices where getConnectionOwnerUid returns netd instead of
 * the app. It is not stored or uploaded.
 */
public final class ForegroundApp {

    private static volatile String packageName = "";
    private static volatile long updatedAt;

    private ForegroundApp() {
    }

    public static void update(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        packageName = pkg;
        updatedAt = SystemClock.elapsedRealtime();
    }

    public static String packageName() {
        if (SystemClock.elapsedRealtime() - updatedAt > 20_000L) {
            return "";
        }
        return packageName;
    }
}
