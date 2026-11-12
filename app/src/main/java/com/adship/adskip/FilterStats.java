package com.adship.adskip;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** In-process counters and a small recent-query ring buffer for the log screen. */
public final class FilterStats {

    private static final int MAX_RECORDS = 300;
    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong BLOCKED = new AtomicLong();
    private static final ArrayDeque<QueryRecord> RECORDS = new ArrayDeque<>();
    private static final Object RECORD_LOCK = new Object();
    private static volatile String lastBlocked = "";

    private FilterStats() {
    }

    public static final class QueryRecord {
        public final long timestamp;
        public final String domain;
        public final boolean blocked;
        public final int type;
        public final String appName;

        QueryRecord(long timestamp, String domain, boolean blocked, int type, String appName) {
            this.timestamp = timestamp;
            this.domain = domain;
            this.blocked = blocked;
            this.type = type;
            this.appName = appName;
        }
    }

    public static void onQuery(String domain, boolean blocked) {
        onQuery(domain, blocked, 0, "未知应用");
    }

    public static void onQuery(String domain, boolean blocked, int type) {
        onQuery(domain, blocked, type, "未知应用");
    }

    public static void onQuery(String domain, boolean blocked, int type, String appName) {
        QUERIES.incrementAndGet();
        if (blocked) {
            BLOCKED.incrementAndGet();
            if (domain != null && !domain.isEmpty()) {
                lastBlocked = domain;
            }
        }
        String name = domain == null || domain.isEmpty() ? "(未解析)" : domain;
        String source = appName == null || appName.isEmpty() ? "未知应用" : appName;
        synchronized (RECORD_LOCK) {
            RECORDS.addLast(new QueryRecord(
                    System.currentTimeMillis(), name, blocked, type, source));
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeFirst();
            }
        }
    }

    public static long queries() {
        return QUERIES.get();
    }

    public static long blocked() {
        return BLOCKED.get();
    }

    public static String lastBlocked() {
        return lastBlocked;
    }

    /** Oldest first. The log screen reverses this for a newest-first list. */
    public static List<QueryRecord> records() {
        synchronized (RECORD_LOCK) {
            return new ArrayList<>(RECORDS);
        }
    }

    public static void clearRecords() {
        synchronized (RECORD_LOCK) {
            RECORDS.clear();
        }
    }

    public static void reset() {
        QUERIES.set(0);
        BLOCKED.set(0);
        lastBlocked = "";
        clearRecords();
    }
}
