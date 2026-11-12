package com.adship.adskip;

import android.content.Context;
import android.util.Log;
import android.widget.EditText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Loads the bundled Chinese ad rule packs into the editable custom-rule fields. */
public final class RulePacks {

    private static final String TAG = "AdSkip";

    public static final String RECOMMENDED = "rules_cn_recommended.txt";
    public static final String AGGRESSIVE = "rules_cn_aggressive.txt";
    public static final String ALLOWLIST = "rules_cn_allowlist.txt";

    private RulePacks() {
    }

    /**
     * Appends valid rules that are not already present.
     *
     * @return number of appended rules, or -1 if the asset could not be read
     */
    public static int append(Context context, String asset, EditText target) {
        String pack;
        try {
            pack = readAsset(context, asset);
        } catch (IOException e) {
            Log.e(TAG, "Could not read " + asset, e);
            return -1;
        }
        return appendText(target, pack);
    }

    /**
     * Appends valid rules from arbitrary text (hosts file, plain domains or AdGuard syntax).
     *
     * @return number of appended rules
     */
    public static int appendText(EditText target, String pack) {
        if (pack == null || pack.isEmpty()) {
            return 0;
        }
        String existing = target.getText().toString();
        Set<String> seen = new HashSet<>();
        for (String line : existing.split("\\r?\\n")) {
            String rule = Blocklist.parseRule(line);
            if (rule != null) {
                seen.add(rule);
            }
        }

        StringBuilder additions = new StringBuilder();
        int added = 0;
        for (String line : pack.split("\\r?\\n")) {
            String rule = Blocklist.parseRule(line);
            if (rule == null || !seen.add(rule)) {
                continue;
            }
            additions.append(rule).append('\n');
            added++;
        }

        if (added > 0) {
            if (!existing.isEmpty() && !existing.endsWith("\n")) {
                target.append("\n");
            }
            target.append(additions);
            target.setSelection(target.getText().length());
        }
        return added;
    }

    private static String readAsset(Context context, String asset) throws IOException {
        StringBuilder text = new StringBuilder(4096);
        try (InputStream raw = context.getAssets().open(asset);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }
}
