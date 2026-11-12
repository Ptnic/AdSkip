package com.adship.adskip;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional, deliberately conservative accessibility helper.
 *
 * <p>It only clicks:</p>
 * <ul>
 *   <li>explicit launch controls such as "跳过" / "跳过广告" / "关闭广告";</li>
 *   <li>a close/feedback control next to a strict "广告 / 推广 / 赞助" label;</li>
 *   <li>a "更多" menu next to that label, then "不感兴趣" / "我不想看" / "关闭广告".</li>
 * </ul>
 *
 * <p>It never uses a generic "关闭" during app launch, never clicks player controls like
 * "跳过片头", and stops after a few actions per foreground app to avoid loops.</p>
 */
public final class AdSkipService extends AccessibilityService {

    private static final long LAUNCH_ARM_MILLIS = 5000L;
    private static final long MENU_WAIT_MILLIS = 2200L;
    private static final long CONFIRM_WAIT_MILLIS = 2200L;
    private static final long SCAN_INTERVAL_MILLIS = 600L;
    private static final long CLOSE_COOLDOWN_MILLIS = 5000L;
    private static final long MENU_FAIL_COOLDOWN_MILLIS = 10000L;
    private static final long MENU_REOPEN_GUARD_MILLIS = 9000L;
    private static final long ZHIHU_CLOSE_COOLDOWN_MILLIS = 9000L;
    private static final String ZHIHU_PACKAGE = "com.zhihu.android";
    private static final int MAX_NODES = 2500;
    private static final int MAX_SCOPE_NODES = 260;
    private static final int MAX_ACTIONS = 4;
    private static final int MAX_MENU_ATTEMPTS = 2;

    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller"
    ));

    private long launchUntil;
    private long menuUntil;
    private long reasonUntil;
    private long confirmUntil;
    private long cooldownUntil;
    private long lastMenuClick;
    private long zhihuCloseUntil;
    private long lastScan;
    private int actions;
    private int menuAttempts;
    private String lastPackage = "";

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        String pkg = context.getPackageName();
        String full = pkg + "/" + AdSkipService.class.getName();
        String shortName = pkg + "/." + AdSkipService.class.getSimpleName();
        return enabled.contains(full) || enabled.contains(shortName);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(DnsConfig.KEY_AUTO_SKIP, false)) {
            return;
        }
        boolean continuous = prefs.getBoolean(DnsConfig.KEY_CONTINUOUS_ADS, false);
        CharSequence packageName = event.getPackageName();
        if (packageName == null) {
            return;
        }
        String pkg = packageName.toString();
        if (pkg.equals(getPackageName()) || IGNORED_PACKAGES.contains(pkg)) {
            return;
        }
        ForegroundApp.update(pkg);

        long now = SystemClock.uptimeMillis();
        if (!pkg.equals(lastPackage)) {
            lastPackage = pkg;
            launchUntil = now + LAUNCH_ARM_MILLIS;
            menuUntil = 0;
            reasonUntil = 0;
            confirmUntil = 0;
            cooldownUntil = 0;
            lastMenuClick = 0;
            zhihuCloseUntil = 0;
            lastScan = 0;
            actions = 0;
            menuAttempts = 0;
        }
        if (now < cooldownUntil || now - lastScan < SCAN_INTERVAL_MILLIS) {
            return;
        }
        lastScan = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        List<NodeRef> nodes = collect(root);
        if (nodes.isEmpty()) {
            return;
        }
        if (!continuous) {
            menuUntil = 0;
            reasonUntil = 0;
            confirmUntil = 0;
        }

        // Reason dialog after "不感兴趣": choose "我不想看" when it is present.
        if (continuous && now < reasonUntil) {
            if (clickFirst(nodes, AdTextMatcher::isAdClose)) {
                reasonUntil = 0;
                confirmUntil = now + CONFIRM_WAIT_MILLIS;
                return;
            }
            if (clickFirst(nodes, AdTextMatcher::isConfirm)) {
                finish(now, CLOSE_COOLDOWN_MILLIS);
            }
            return;
        }

        // After "不想看", some apps show a confirm button.
        if (continuous && now < confirmUntil) {
            if (clickFirst(nodes, AdTextMatcher::isConfirm)) {
                finish(now, CLOSE_COOLDOWN_MILLIS);
            }
            return;
        }

        // A "更多" menu was opened for an ad. Only handle that menu, then bail out.
        if (continuous && now < menuUntil) {
            if (clickFirst(nodes, AdTextMatcher::isAdClose)) {
                menuUntil = 0;
                reasonUntil = now + CONFIRM_WAIT_MILLIS;
                return;
            }
            if (clickFirst(nodes, AdTextMatcher::isGenericClose)) {
                finish(now, CLOSE_COOLDOWN_MILLIS);
                return;
            }
            if (now + 300 >= menuUntil) {
                // Nothing suitable in the menu: leave it instead of clicking more random things.
                performGlobalAction(GLOBAL_ACTION_BACK);
                menuUntil = 0;
                menuAttempts++;
                cooldownUntil = now + MENU_FAIL_COOLDOWN_MILLIS;
            }
            return;
        }

        // Launch: only explicit skip labels; no generic "关闭".
        if (now < launchUntil && actions < MAX_ACTIONS
                && clickFirst(nodes, AdTextMatcher::isLaunchSkip)) {
            finish(now, CLOSE_COOLDOWN_MILLIS);
            return;
        }

        if (!continuous) {
            return;
        }

        // Ad card / video banner: require an explicit ad label next to the control.
        if (actions < MAX_ACTIONS && menuAttempts < MAX_MENU_ATTEMPTS
                && tryAdCardActions(nodes, now)) {
            return;
        }

        // Zhihu answer-page promotion uses a small X button without a "广告" label.
        // Restrict this to a small, explicitly labelled close control and keep a long cooldown.
        if (ZHIHU_PACKAGE.equals(pkg)
                && actions < MAX_ACTIONS
                && now > launchUntil
                && now >= zhihuCloseUntil
                && tryZhihuClose(nodes, now)) {
            return;
        }
    }

    private boolean tryZhihuClose(List<NodeRef> nodes, long now) {
        int maxSize = (int) (96 * getResources().getDisplayMetrics().density);
        for (NodeRef ref : nodes) {
            if (!visible(ref.node)) {
                continue;
            }
            String text = textOf(ref.node).trim().toLowerCase(Locale.ROOT);
            if (!(text.equals("×") || text.equals("x") || text.equals("关闭")
                    || text.equals("关闭广告") || text.equals("关闭推广"))) {
                continue;
            }
            Rect bounds = new Rect();
            try {
                ref.node.getBoundsInScreen(bounds);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (bounds.width() <= 0 || bounds.height() <= 0
                    || bounds.width() > maxSize || bounds.height() > maxSize) {
                continue;
            }
            if (clickNode(ref.node)) {
                zhihuCloseUntil = now + ZHIHU_CLOSE_COOLDOWN_MILLIS;
                finish(now, CLOSE_COOLDOWN_MILLIS);
                return true;
            }
        }
        return false;
    }

    private boolean tryAdCardActions(List<NodeRef> nodes, long now) {
        int markers = 0;
        for (NodeRef marker : nodes) {
            if (!visible(marker.node) || !AdTextMatcher.isAdMarker(textOf(marker.node))) {
                continue;
            }
            if (++markers > 6) {
                break;
            }
            NodeRef scope = marker.parent;
            if (scope == null) {
                continue;
            }

            // Direct close is safer than opening a menu.
            if (clickInScope(scope.node, AdTextMatcher::isAdClose)
                    || clickInScope(scope.node, AdTextMatcher::isGenericClose)) {
                finish(now, CLOSE_COOLDOWN_MILLIS);
                return true;
            }

            if (scope.parent != null
                    && now - lastMenuClick > MENU_REOPEN_GUARD_MILLIS
                    && (clickInScope(scope.node, AdTextMatcher::isMenu)
                    || clickInScope(scope.parent.node, AdTextMatcher::isMenu))) {
                menuUntil = now + MENU_WAIT_MILLIS;
                lastMenuClick = now;
                menuAttempts++;
                launchUntil = 0;
                return true;
            }
        }
        return false;
    }

    private boolean clickInScope(AccessibilityNodeInfo scope, TextPredicate predicate) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(scope);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_SCOPE_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (visible(node) && predicate.matches(textOf(node)) && clickNode(node)) {
                    return true;
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            } catch (RuntimeException ignored) {
                // The node may have become stale.
            }
        }
        return false;
    }

    private boolean clickFirst(List<NodeRef> nodes, TextPredicate predicate) {
        for (NodeRef ref : nodes) {
            try {
                if (visible(ref.node) && predicate.matches(textOf(ref.node))
                        && clickNode(ref.node)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // The node may have become stale.
            }
        }
        return false;
    }

    private void finish(long now, long cooldown) {
        actions++;
        launchUntil = 0;
        menuUntil = 0;
        reasonUntil = 0;
        confirmUntil = 0;
        cooldownUntil = now + cooldown;
    }

    private List<NodeRef> collect(AccessibilityNodeInfo root) {
        List<NodeRef> nodes = new ArrayList<>();
        ArrayDeque<NodeRef> queue = new ArrayDeque<>();
        queue.add(new NodeRef(root, null, 0));
        while (!queue.isEmpty() && nodes.size() < MAX_NODES) {
            NodeRef ref = queue.removeFirst();
            nodes.add(ref);
            try {
                if (ref.depth >= 30) {
                    continue;
                }
                int count = ref.node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = ref.node.getChild(i);
                    if (child != null) {
                        queue.addLast(new NodeRef(child, ref, ref.depth + 1));
                    }
                }
            } catch (RuntimeException ignored) {
                // The node may have become stale.
            }
        }
        return nodes;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 4 && current != null; i++) {
            try {
                if (current.isClickable()
                        && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                current = current.getParent();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean visible(AccessibilityNodeInfo node) {
        try {
            return node != null && node.isVisibleToUser();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String textOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text == null && description == null) {
            return "";
        }
        if (text == null) {
            return description.toString();
        }
        if (description == null) {
            return text.toString();
        }
        return text + " " + description;
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: each event is handled synchronously.
    }

    private interface TextPredicate {
        boolean matches(String text);
    }

    private static final class NodeRef {
        final AccessibilityNodeInfo node;
        final NodeRef parent;
        final int depth;

        NodeRef(AccessibilityNodeInfo node, NodeRef parent, int depth) {
            this.node = node;
            this.parent = parent;
            this.depth = depth;
        }
    }
}
