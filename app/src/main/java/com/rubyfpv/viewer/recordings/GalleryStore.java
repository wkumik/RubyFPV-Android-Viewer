package com.rubyfpv.viewer.recordings;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@link MediaStore} for the {@code Movies/RubyFPV} video collection — the
 * single store of record for downloaded+converted clips.
 *
 * <p>On API 29+ files live in the public {@code Movies/RubyFPV} relative path,
 * owned by this app (so we may delete them without a {@code RecoverableSecurity-
 * Exception}). They appear in Gallery/Photos and survive an app uninstall. On
 * pre-Q we write into the public {@code Movies/RubyFPV} dir and register a
 * MediaStore row.</p>
 */
public final class GalleryStore {

    /** Public sub-album under Movies. */
    private static final String SUBDIR = "RubyFPV";
    /** Q+ RELATIVE_PATH value, e.g. "Movies/RubyFPV". */
    private static final String REL_PATH = Environment.DIRECTORY_MOVIES + "/" + SUBDIR;

    private GalleryStore() {}

    /** A clip discovered in the gallery collection. */
    public static final class GalleryItem {
        public Uri uri;
        public String displayName;
        public String stem;
        public long sizeBytes;
        public long mtimeEpochSec;
        public long durationMs = -1;
    }

    private static Uri videoCollection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    private static String ensureMp4(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "clip.mp4";
        return displayName.endsWith(".mp4") ? displayName : displayName + ".mp4";
    }

    /**
     * Publish {@code mp4}'s bytes into {@code Movies/RubyFPV} as {@code displayName}.
     *
     * @return the published content {@link Uri}, or {@code null} on failure.
     */
    public static Uri publish(Context ctx, File mp4, String displayName) {
        if (mp4 == null || !mp4.exists() || mp4.length() == 0) {
            DebugLog.add("gallery publish: source missing/empty " + mp4);
            return null;
        }
        final String name = ensureMp4(displayName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishQ(ctx, mp4, name);
        }
        return publishLegacy(ctx, mp4, name);
    }

    private static Uri publishQ(Context ctx, File mp4, String name) {
        final ContentResolver cr = ctx.getContentResolver();
        final Uri collection = videoCollection();

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, REL_PATH);
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = cr.insert(collection, cv);
            if (uri == null) {
                DebugLog.add("gallery publish: insert returned null for " + name);
                return null;
            }
            try (OutputStream out = cr.openOutputStream(uri);
                 InputStream in = new FileInputStream(mp4)) {
                if (out == null) throw new java.io.IOException("openOutputStream null");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues clear = new ContentValues();
            clear.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(uri, clear, null, null);
            DebugLog.add("gallery publish OK: " + name + " -> " + uri);
            return uri;
        } catch (Exception e) {
            DebugLog.add("gallery publish FAILED (" + name + "): " + e.getMessage());
            if (uri != null) {
                try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private static Uri publishLegacy(Context ctx, File mp4, String name) {
        try {
            File baseDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    SUBDIR);
            //noinspection ResultOfMethodCallIgnored
            baseDir.mkdirs();
            File out = new File(baseDir, name);
            try (InputStream in = new FileInputStream(mp4);
                 OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.DATA, out.getAbsolutePath());
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            DebugLog.add("gallery publish (legacy) "
                    + (uri != null ? "OK" : "row null") + ": " + out.getAbsolutePath());
            return uri;
        } catch (Exception e) {
            DebugLog.add("gallery publish (legacy) FAILED (" + name + "): " + e.getMessage());
            return null;
        }
    }

    /** List clips under {@code Movies/RubyFPV}. Newest items are not guaranteed first. */
    public static List<GalleryItem> query(Context ctx) {
        List<GalleryItem> out = new ArrayList<>();
        final Uri collection = videoCollection();
        final boolean q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

        String[] projection = q
                ? new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED,
                        MediaStore.Video.Media.DURATION,
                }
                : new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.DATA,
                };

        String selection;
        String[] args;
        if (q) {
            selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{ REL_PATH + "%" };
        } else {
            selection = MediaStore.Video.Media.DATA + " LIKE ?";
            args = new String[]{ "%/" + REL_PATH + "/%" };
        }

        try (Cursor c = ctx.getContentResolver().query(
                collection, projection, selection, args,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            while (c.moveToNext()) {
                GalleryItem g = new GalleryItem();
                long id = c.getLong(idCol);
                g.uri = ContentUris.withAppendedId(collection, id);
                g.displayName = c.getString(nameCol);
                g.stem = stemOf(g.displayName);
                g.sizeBytes = c.getLong(sizeCol);
                g.mtimeEpochSec = c.getLong(dateCol);
                long dur = c.isNull(durCol) ? -1 : c.getLong(durCol);
                g.durationMs = dur > 0 ? dur : -1;
                out.add(g);
            }
            DebugLog.add("gallery query: " + out.size() + " item(s)");
        } catch (Exception e) {
            DebugLog.add("gallery query FAILED: " + e.getMessage());
        }
        return out;
    }

    /** Delete a clip we created. App owns it on Q+, so no recoverable-security prompt. */
    public static boolean delete(Context ctx, Uri uri) {
        if (uri == null) return false;
        try {
            int n = ctx.getContentResolver().delete(uri, null, null);
            DebugLog.add("gallery delete " + (n > 0 ? "OK" : "no-op") + ": " + uri);
            return n > 0;
        } catch (Exception e) {
            DebugLog.add("gallery delete FAILED (" + uri + "): " + e.getMessage());
            return false;
        }
    }

    private static String stemOf(String displayName) {
        if (displayName == null) return "";
        return displayName.endsWith(".mp4")
                ? displayName.substring(0, displayName.length() - 4)
                : displayName;
    }
}
