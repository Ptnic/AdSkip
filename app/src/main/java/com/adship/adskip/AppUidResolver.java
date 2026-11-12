package com.adship.adskip;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.system.OsConstants;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort attribution of a TUN DNS packet to an app.
 *
 * <p>Android 10+ can map a UDP socket to its owner UID. On some devices the system DNS
 * resolver owns the socket, so we fall back to the last foreground app observed by the
 * optional accessibility service. If neither is available, the log shows "未知应用".</p>
 */
public final class AppUidResolver {

    private static final int UID_SYSTEM = 1000;
    private static final int UID_NETD = 1052;
    private static final byte[] VIRTUAL_DNS =
            new byte[]{10, 111, (byte) 222, 2};
    private static final Map<String, String> SHORT_NAMES = new HashMap<>();

    static {
        SHORT_NAMES.put("tv.danmaku.bili", "B站");
        SHORT_NAMES.put("tv.danmaku.bilibilihd", "B站 HD");
        SHORT_NAMES.put("com.bilibili.app.in", "B站");
        SHORT_NAMES.put("com.zhihu.android", "知乎");
        SHORT_NAMES.put("com.dragon.read", "番茄小说");
        SHORT_NAMES.put("com.dragon.read.pad", "番茄小说");
        SHORT_NAMES.put("com.ss.android.ugc.aweme", "抖音");
        SHORT_NAMES.put("com.ss.android.ugc.aweme.lite", "抖音极速版");
        SHORT_NAMES.put("com.ss.android.ugc.live", "抖音");
        SHORT_NAMES.put("com.ss.android.article.news", "今日头条");
        SHORT_NAMES.put("com.ss.android.article.lite", "今日头条极速版");
        SHORT_NAMES.put("com.smile.gifmaker", "快手");
        SHORT_NAMES.put("com.kuaishou.nebula", "快手极速版");
        SHORT_NAMES.put("com.xingin.xhs", "小红书");
        SHORT_NAMES.put("com.sina.weibo", "微博");
        SHORT_NAMES.put("com.tencent.mm", "微信");
        SHORT_NAMES.put("com.tencent.mobileqq", "QQ");
        SHORT_NAMES.put("com.taobao.taobao", "淘宝");
        SHORT_NAMES.put("com.jingdong.app.mall", "京东");
        SHORT_NAMES.put("com.eg.android.AlipayGphone", "支付宝");
        SHORT_NAMES.put("com.tencent.qqlive", "腾讯视频");
        SHORT_NAMES.put("com.qiyi.video", "爱奇艺");
        SHORT_NAMES.put("com.youku.phone", "优酷");
        SHORT_NAMES.put("com.hunantv.imgo.activity", "芒果TV");
        SHORT_NAMES.put("com.netease.cloudmusic", "网易云音乐");
        SHORT_NAMES.put("com.tencent.qqmusic", "QQ音乐");
        SHORT_NAMES.put("com.kugou.android", "酷狗音乐");
    }

    private final ConnectivityManager connectivityManager;
    private final PackageManager packageManager;
    private final ConcurrentHashMap<Integer, String> packageByUid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelByPackage = new ConcurrentHashMap<>();

    public AppUidResolver(Context context) {
        connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        packageManager = context.getPackageManager();
    }

    public ResolvedApp resolve(byte[] sourceIp, int sourcePort) {
        int uid = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && connectivityManager != null
                && sourceIp != null
                && sourceIp.length == 4) {
            try {
                InetAddress local = InetAddress.getByAddress(sourceIp);
                InetAddress dns = InetAddress.getByAddress(VIRTUAL_DNS);
                uid = connectivityManager.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        new InetSocketAddress(local, sourcePort),
                        new InetSocketAddress(dns, 53));
            } catch (Exception ignored) {
                // Fall through to the foreground-app fallback.
            }
        }

        String pkg = packageForUid(uid);
        String label = labelForPackage(pkg);
        if (label != null && uid != UID_SYSTEM && uid != UID_NETD
                && !pkg.startsWith("android.uid.")) {
            return new ResolvedApp(label, pkg);
        }

        String foreground = ForegroundApp.packageName();
        String foregroundLabel = labelForPackage(foreground);
        if (foregroundLabel != null) {
            return new ResolvedApp(foregroundLabel, foreground);
        }

        if (uid == UID_NETD) {
            return new ResolvedApp("系统 DNS", "");
        }
        if (uid == UID_SYSTEM) {
            return new ResolvedApp("系统", "");
        }
        return new ResolvedApp("未知应用", "");
    }

    private String packageForUid(int uid) {
        if (uid < 0) {
            return "";
        }
        String cached = packageByUid.get(uid);
        if (cached != null) {
            return cached;
        }
        String pkg = "";
        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                pkg = packages[0];
                for (String candidate : packages) {
                    if (SHORT_NAMES.containsKey(candidate)) {
                        pkg = candidate;
                        break;
                    }
                }
            } else {
                String name = packageManager.getNameForUid(uid);
                if (name != null) {
                    pkg = name;
                }
            }
        } catch (RuntimeException ignored) {
            // Some system UIDs are not resolvable through PackageManager.
        }
        packageByUid.put(uid, pkg);
        return pkg;
    }

    private String labelForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        String shortName = SHORT_NAMES.get(pkg);
        if (shortName != null) {
            return shortName;
        }
        String cached = labelByPackage.get(pkg);
        if (cached != null) {
            return cached;
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(pkg, 0);
            CharSequence label = info.loadLabel(packageManager);
            String value = label == null ? "" : label.toString().trim();
            if (value.isEmpty() || value.equals(pkg)
                    || (value.startsWith("com.") && !value.contains(" "))) {
                // Some packages expose their package name as the label; do not show that.
                return null;
            }
            labelByPackage.put(pkg, value);
            return value;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static final class ResolvedApp {
        public final String label;
        public final String packageName;

        ResolvedApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
