package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;

/** First-frame thumbnails (DJI-style) + duration, via MediaMetadataRetriever. */
public final class Thumbs {

    private static final int MAX_W = 640;

    public static final LruCache<String, Bitmap> CACHE;

    static {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        CACHE = new LruCache<String, Bitmap>(kb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private Thumbs() {}

    public static class Meta {
        public Bitmap bitmap;
        public long durationMs = -1;
    }

    public static Meta extract(File file, String stem) {
        Meta m = new Meta();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try { m.durationMs = Long.parseLong(dur); } catch (NumberFormatException ignored) {}
            }
            Bitmap frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                m.bitmap = scale(frame);
                if (m.bitmap != frame) frame.recycle();
                if (stem != null && m.bitmap != null) CACHE.put(stem, m.bitmap);
            }
        } catch (Exception ignored) {
            // unreadable clip — leave bitmap null, caller shows placeholder
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return m;
    }

    /** Persist a thumbnail as JPEG so it survives app restarts (true preload). */
    public static void saveDisk(File thumbsDir, String stem, Bitmap bmp) {
        if (bmp == null) return;
        //noinspection ResultOfMethodCallIgnored
        thumbsDir.mkdirs();
        File f = new File(thumbsDir, stem + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (Exception ignored) {
        }
    }

    /** Load all on-disk thumbnails into the in-memory cache at startup. */
    public static void loadDiskInto(File thumbsDir) {
        File[] files = thumbsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".jpg")) continue;
            String stem = name.substring(0, name.length() - 4);
            if (CACHE.get(stem) != null) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) CACHE.put(stem, b);
        }
    }

    private static Bitmap scale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_W || w == 0) return src;
        float ratio = (float) MAX_W / w;
        return Bitmap.createScaledBitmap(src, MAX_W, Math.round(h * ratio), true);
    }
}
