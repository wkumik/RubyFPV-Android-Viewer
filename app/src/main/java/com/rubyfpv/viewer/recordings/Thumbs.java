package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.File;

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

    private static Bitmap scale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_W || w == 0) return src;
        float ratio = (float) MAX_W / w;
        return Bitmap.createScaledBitmap(src, MAX_W, Math.round(h * ratio), true);
    }
}
