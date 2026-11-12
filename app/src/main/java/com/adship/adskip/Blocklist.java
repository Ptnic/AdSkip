package com.adship.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Immutable domain matcher. A blocked parent domain also blocks all of its subdomains.
 * Whitelist entries win over blocklist entries, including for subdomains.
 */
public final class Blocklist {

    private static final String TAG = "AdSkip";
    // Despite the .dat extension, the payload is gzip-compressed. Avoid a .gz suffix:
    // Android's asset packaging may transparently expand it and strip the suffix.
    private static final String ASSET = "blocklist.dat";

    private final HashSet<String> blocked;
    private final HashSet<String> allowed;

    private Blocklist(HashSet<String> blocked, HashSet<String> allowed) {
        this.blocked = blocked;
        this.allowed = allowed;
    }

    public static Blocklist load(Context context) {
        HashSet<String> blocked = new HashSet<>(131072);
        readAsset(context, blocked);

        SharedPreferences p = context.getSharedPreferences(DnsConfig.PREFS, Context.MODE_PRIVATE);
        readRules(p.getString(DnsConfig.KEY_CUSTOM_BLOCK, ""), blocked);

        HashSet<String> allowed = new HashSet<>(256);
        readRules(p.getString(DnsConfig.KEY_CUSTOM_ALLOW, ""), allowed);

        Log.i(TAG, "Blocklist loaded: " + blocked.size() + " blocked, " + allowed.size() + " allowed");
        return new Blocklist(blocked, allowed);
    }

    private static void readAsset(Context context, HashSet<String> out) {
        try (InputStream raw = context.getAssets().open(ASSET);
             InputStream gzip = new GZIPInputStream(raw, 65536);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 65536)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = parseRule(line);
                if (domain != null) {
                    out.add(domain);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not read bundled blocklist", e);
        }
    }

    private static void readRules(String text, HashSet<String> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String domain = parseRule(line);
            if (domain != null) {
                out.add(domain);
            }
        }
    }

    /** Supports hosts lines, plain domains and AdGuard's ||domain^ syntax. */
    static String parseRule(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
            return null;
        }
        int comment = line.indexOf('#');
        if (comment >= 0) {
            line = line.substring(0, comment).trim();
        }
        if (line.isEmpty()) {
            return null;
        }
        if (line.startsWith("||")) {
            line = line.substring(2);
            int end = line.indexOf('^');
            if (end >= 0) {
                line = line.substring(0, end);
            }
        } else {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 && isSinkAddress(parts[0])) {
                line = parts[1];
            } else {
                line = parts[0];
            }
        }
        if (line.startsWith("*.")) {
            line = line.substring(2);
        }
        line = DnsConfig.normalizeDomain(line);
        if (line.isEmpty() || line.length() > 253 || !line.contains(".")) {
            return null;
        }
        if (line.equals("localhost") || line.equals("localhost.localdomain")
                || line.equals("broadcasthost") || line.startsWith("ip6-")) {
            return null;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        if (line.startsWith(".") || line.endsWith(".") || line.contains("..")) {
            return null;
        }
        return line;
    }

    private static boolean isSinkAddress(String value) {
        return "0.0.0.0".equals(value) || "127.0.0.1".equals(value)
                || "::".equals(value) || "::1".equals(value);
    }

    public boolean isBlocked(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        String value = domain.toLowerCase(Locale.ROOT);
        if (matches(allowed, value)) {
            return false;
        }
        return matches(blocked, value);
    }

    /** Exact match first, then every parent label: a.b.example.com -> b.example.com -> example.com. */
    private static boolean matches(HashSet<String> set, String domain) {
        String value = domain;
        while (true) {
            if (set.contains(value)) {
                return true;
            }
            int dot = value.indexOf('.');
            if (dot < 0 || dot == value.length() - 1) {
                return false;
            }
            value = value.substring(dot + 1);
        }
    }

    public int size() {
        return blocked.size();
    }
}
