package com.rubyfpv.viewer.recordings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.ExperimentalFrameExtractor;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * First-frame thumbnails (DJI-style).
 *
 * <p>The clips are HEVC in an MPEG-TS container. Android's framework demuxer
 * ({@link android.media.MediaExtractor} / {@link android.media.MediaMetadataRetriever})
 * <b>cannot</b> demux HEVC-in-TS — it opens the file but reports zero tracks — so
 * every framework path (MMR, remux-to-MP4, MediaCodec) fails. media3's
 * {@link ExperimentalFrameExtractor} runs the same ExoPlayer pipeline that plays
 * the clips, whose {@code TsExtractor} <i>does</i> support HEVC, so it decodes the
 * first frame directly from the {@code .ts} (or a downloaded {@code .ts} prefix).</p>
 */
public final class Thumbs {

    private static final int MAX_W = 640;
    private static final long FRAME_TIMEOUT_S = 20;

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
        public long durationMs = -1;   // best-effort; raw TS carries no duration header
    }

    public static Meta extract(Context ctx, File file, String stem) {
        Meta m = new Meta();
        String id = stem != null ? stem : file.getName();
        DebugLog.add("extract " + id + " (" + file.length() + " B)");

        m.bitmap = frame(ctx, file);
        DebugLog.add("  media3-frame: " + (m.bitmap != null ? "OK" : "null"));

        if (m.bitmap != null) {
            m.bitmap = scale(m.bitmap);
            if (stem != null) CACHE.put(stem, m.bitmap);
            DebugLog.add("  => thumb OK for " + id);
        } else {
            DebugLog.add("  => thumb FAILED for " + id);
        }
        return m;
    }

    /**
     * Decode the first frame via media3's ExoPlayer pipeline. Safe to call from a
     * plain background thread (the extractor runs its own internal playback looper);
     * blocks until the frame is ready or {@link #FRAME_TIMEOUT_S} elapses.
     */
    private static Bitmap frame(Context ctx, File file) {
        ExperimentalFrameExtractor fe = new ExperimentalFrameExtractor(
                ctx.getApplicationContext(),
                new ExperimentalFrameExtractor.Configuration.Builder().build());
        try {
            fe.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)),
                    Collections.<Effect>emptyList());
            ListenableFuture<ExperimentalFrameExtractor.Frame> future = fe.getFrame(0);
            ExperimentalFrameExtractor.Frame f = future.get(FRAME_TIMEOUT_S, TimeUnit.SECONDS);
            return f != null ? f.bitmap : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { fe.release(); } catch (Exception ignored) {}
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

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

    // ── Scaling ─────────────────────────────────────────────────────────

    private static Bitmap scale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_W || w == 0) return src;
        float ratio = (float) MAX_W / w;
        Bitmap scaled = Bitmap.createScaledBitmap(src, MAX_W, Math.round(h * ratio), true);
        if (scaled != src) src.recycle();
        return scaled;
    }
}
