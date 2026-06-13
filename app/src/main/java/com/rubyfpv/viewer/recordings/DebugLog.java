package com.rubyfpv.viewer.recordings;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Tiny in-app ring-buffer log so the thumbnail/transfer pipeline can be inspected
 * on-device (⋮ → Diagnostics) without needing adb/logcat.
 */
public final class DebugLog {

    private static final String TAG = "RubyDiag";
    private static final int MAX = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final long T0 = SystemClock.uptimeMillis();

    private DebugLog() {}

    public static synchronized void add(String msg) {
        String line = String.format(Locale.US, "%6.1fs  %s",
                (SystemClock.uptimeMillis() - T0) / 1000.0, msg);
        LINES.addLast(line);
        while (LINES.size() > MAX) LINES.removeFirst();
        Log.i(TAG, msg);
    }

    public static synchronized String dump() {
        if (LINES.isEmpty()) return "(no diagnostics yet — connect and let thumbnails load)";
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
