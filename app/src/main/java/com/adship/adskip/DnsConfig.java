package com.adship.adskip;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * All user-configurable state lives here. The service reads one immutable snapshot when it
 * starts, so changing a field can never corrupt a running tunnel.
 */
public final class DnsConfig {

    public static final String PREFS = "adskip_prefs";
    public static final String KEY_MODE = "dns_mode";
    public static final String KEY_ADDRESS = "dns_address";
    public static final String KEY_BLOCK_ADS = "block_ads";
    public static final String KEY_BOOT_START = "boot_start";
    public static final String KEY_CUSTOM_BLOCK = "custom_block";
    public static final String KEY_CUSTOM_ALLOW = "custom_allow";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_AUTO_SKIP = "auto_skip";
    public static final String KEY_SHOW_ALLOWED = "show_allowed";
    public static final String KEY_CONTINUOUS_ADS = "continuous_ads";

    public enum Mode {
        UDP, DOH, DOT;

        public static Mode from(String raw) {
            if (raw != null) {
                try {
                    return Mode.valueOf(raw);
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the safe default.
                }
            }
            return UDP;
        }
    }

    public final Mode mode;
    public final String address;
    public final boolean blockAds;
    public final boolean startOnBoot;

    public DnsConfig(Mode mode, String address, boolean blockAds, boolean startOnBoot) {
        this.mode = mode == null ? Mode.UDP : mode;
        this.address = address == null ? "" : address.trim();
        this.blockAds = blockAds;
        this.startOnBoot = startOnBoot;
    }

    public static DnsConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Mode mode = Mode.from(p.getString(KEY_MODE, Mode.UDP.name()));
        String address = p.getString(KEY_ADDRESS, defaultAddress(mode));
        if (address == null || address.trim().isEmpty()) {
            address = defaultAddress(mode);
        }
        return new DnsConfig(
                mode,
                address,
                p.getBoolean(KEY_BLOCK_ADS, true),
                p.getBoolean(KEY_BOOT_START, false));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name())
                .putString(KEY_ADDRESS, address)
                .putBoolean(KEY_BLOCK_ADS, blockAds)
                .putBoolean(KEY_BOOT_START, startOnBoot)
                .apply();
    }

    public static String defaultAddress(Mode mode) {
        switch (mode) {
            case DOH:
                return "https://dns.alidns.com/dns-query";
            case DOT:
                return "dns.alidns.com:853";
            case UDP:
            default:
                return "223.5.5.5:53";
        }
    }

    public static String presetAddress(Mode mode, int index) {
        String[] values;
        switch (mode) {
            case DOH:
                values = new String[]{
                        "https://dns.alidns.com/dns-query",
                        "https://doh.pub/dns-query",
                        "https://cloudflare-dns.com/dns-query",
                        "https://dns.google/dns-query"
                };
                break;
            case DOT:
                values = new String[]{
                        "dns.alidns.com:853",
                        "dot.pub:853",
                        "dns.cloudflare.com:853",
                        "dns.google:853"
                };
                break;
            case UDP:
            default:
                values = new String[]{
                        "223.5.5.5:53",
                        "119.29.29.29:53",
                        "1.1.1.1:53",
                        "8.8.8.8:53"
                };
                break;
        }
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index];
    }

    public boolean isValid() {
        if (address.isEmpty()) {
            return false;
        }
        switch (mode) {
            case DOH:
                try {
                    URI uri = new URI(address);
                    if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        return false;
                    }
                    if (uri.getHost() == null || uri.getHost().isEmpty()) {
                        return false;
                    }
                    int port = uri.getPort();
                    return port == -1 || (port > 0 && port <= 65535);
                } catch (Exception e) {
                    return false;
                }
            case DOT: {
                HostPort hostPort = HostPort.parse(address, 853);
                return hostPort != null
                        && hostPort.port > 0
                        && hostPort.port <= 65535
                        && isHostnameOrIp(hostPort.host);
            }
            case UDP:
            default: {
                HostPort hostPort = HostPort.parse(address, 53);
                return hostPort != null
                        && hostPort.port > 0
                        && hostPort.port <= 65535
                        && isIpLiteral(hostPort.host);
            }
        }
    }

    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            // IPv6 literal. The parser accepts only hex digits, colons and dots.
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (!(c == ':' || c == '.' || (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return false;
                }
            }
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHostnameOrIp(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (isIpLiteral(host)) {
            return true;
        }
        if (host.length() > 253 || host.startsWith(".") || host.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return host.contains(".");
    }

    public static String normalizeDomain(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * Parses "1.1.1.1:53", "dns.example:853", "[2606:4700::1111]:53" or a bare host.
     */
    public static final class HostPort {
        public final String host;
        public final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public static HostPort parse(String raw, int defaultPort) {
            if (raw == null) {
                return null;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                return null;
            }
            if (value.startsWith("[")) {
                int end = value.indexOf(']');
                if (end < 0) {
                    return null;
                }
                String host = value.substring(1, end);
                int port = defaultPort;
                if (end + 1 < value.length()) {
                    if (value.charAt(end + 1) != ':') {
                        return null;
                    }
                    port = parsePort(value.substring(end + 2));
                    if (port < 0) {
                        return null;
                    }
                }
                return host.isEmpty() ? null : new HostPort(host, port);
            }
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                String host = value.substring(0, firstColon);
                int port = parsePort(value.substring(firstColon + 1));
                if (port < 0 || host.isEmpty()) {
                    return null;
                }
                return new HostPort(host, port);
            }
            // Bare IPv6 (many colons) or a host without a port.
            return new HostPort(value, defaultPort);
        }

        private static int parsePort(String raw) {
            if (raw == null || raw.isEmpty() || raw.length() > 5) {
                return -1;
            }
            for (int i = 0; i < raw.length(); i++) {
                if (!Character.isDigit(raw.charAt(i))) {
                    return -1;
                }
            }
            try {
                int port = Integer.parseInt(raw);
                return (port > 0 && port <= 65535) ? port : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
