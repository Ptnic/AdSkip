package com.adship.adskip;

import java.util.Locale;

/** Pure text rules used by the accessibility skipper. Kept separate so they are easy to audit. */
public final class AdTextMatcher {

    private AdTextMatcher() {
    }

    /** Strict ad label: "广告", "广告 5s", "推广", "赞助", "Sponsored" ... */
    public static boolean isAdMarker(String raw) {
        String text = normalize(raw);
        if (text.isEmpty() || text.length() > 16) {
            return false;
        }
        return text.equals("广告")
                || text.startsWith("广告 ")
                || text.startsWith("广告·")
                || text.startsWith("广告|")
                || text.equals("推广")
                || text.startsWith("推广 ")
                || text.equals("赞助")
                || text.startsWith("赞助 ")
                || text.equals("商业推广")
                || text.equals("sponsored")
                || text.equals("promoted")
                || text.equals("advertisement")
                || text.equals("ad");
    }

    /** Launch-only: explicit skip labels. Never a generic "关闭". */
    public static boolean isLaunchSkip(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) {
            return false;
        }
        if (text.contains("跳过") || text.contains("跳過")) {
            return !text.contains("片头") && !text.contains("片尾")
                    && !text.contains("剧情") && !text.contains("章节")
                    && !text.contains("开头");
        }
        if (text.contains("关闭开屏") || text.contains("关闭广告")
                || text.contains("广告关闭")) {
            return true;
        }
        return text.contains("skip")
                && !text.contains("intro")
                && !text.contains("credit")
                && !text.contains("recap");
    }

    /** Ad-context close / feedback labels, including B站's reason option "我不想看". */
    public static boolean isAdClose(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) {
            return false;
        }
        return text.contains("关闭广告")
                || text.contains("广告关闭")
                || text.contains("关闭此广告")
                || text.contains("关闭推广")
                || text.contains("不感兴趣")
                || text.contains("我不想看")
                || text.contains("不想看")
                || text.contains("减少推荐")
                || text.contains("减少此类")
                || text.contains("屏蔽此广告")
                || text.contains("隐藏此广告")
                || text.contains("关闭推荐")
                || text.contains("对此内容不感兴趣");
    }

    /** Confirmation button after selecting a "not interested" reason. */
    public static boolean isConfirm(String raw) {
        String text = normalize(raw);
        return text.equals("确定")
                || text.equals("确认")
                || text.equals("提交")
                || text.equals("完成")
                || text.equals("完成选择");
    }

    /** Exact generic close labels, only used in an already-identified ad context. */
    public static boolean isGenericClose(String raw) {
        String text = normalize(raw);
        return text.equals("关闭")
                || text.equals("关闭弹窗")
                || text.equals("close")
                || text.equals("×")
                || text.equals("x");
    }

    /** "更多" / "..." / "更多选项" / "广告设置" etc. */
    public static boolean isMenu(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) {
            return false;
        }
        return text.equals("更多")
                || text.startsWith("更多选项")
                || text.startsWith("更多功能")
                || text.startsWith("更多操作")
                || text.equals("菜单")
                || text.contains("广告设置")
                || text.equals("more")
                || text.equals("...")
                || text.equals("…")
                || text.equals("···")
                || text.equals("⋮");
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
