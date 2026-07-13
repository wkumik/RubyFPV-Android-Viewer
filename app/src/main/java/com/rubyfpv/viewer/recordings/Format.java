package com.rubyfpv.viewer.recordings;

import android.text.format.DateUtils;

import java.util.Locale;

/** Small formatting helpers for sizes, durations and dates. */
public final class Format {

    private Format() {}

    public static String size(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String clock(long ms) {
        if (ms <= 0) return "";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public static String date(long epochSec) {
        if (epochSec <= 0) return "";
        long now = System.currentTimeMillis();
        return DateUtils.getRelativeTimeSpanString(
                epochSec * 1000L, now, DateUtils.MINUTE_IN_MILLIS).toString();
    }
}
