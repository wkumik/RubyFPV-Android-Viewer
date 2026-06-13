package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * First-frame thumbnails (DJI-style) + duration.
 *
 * <p>Android's {@link MediaMetadataRetriever} can't decode MPEG-TS (returns null),
 * and the preload path feeds a partial {@code .ts} prefix. So {@link #extract}
 * tries, in order of reliability:</p>
 * <ol>
 *   <li>MMR directly — works for {@code .mp4};</li>
 *   <li>remux the input to a tiny {@code .mp4} (same {@link Remuxer} that builds the
 *       playable clips) then MMR — the workhorse for {@code .ts};</li>
 *   <li>a {@link MediaCodec} first-frame decode as a last resort.</li>
 * </ol>
 */
public final class Thumbs {

    private static final String TAG = "Thumbs";
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

        // 1) MMR directly (works for .mp4)
        m.bitmap = mmrFrame(file);
        m.durationMs = mmrDuration(file);
        String how = "mmr";

        // 2) remux to a small .mp4 then MMR (the .ts / prefix path)
        if (m.bitmap == null) {
            File tmp = new File(file.getAbsolutePath() + ".thumb.mp4");
            try {
                if (Remuxer.remux(file, tmp)) {
                    m.bitmap = mmrFrame(tmp);
                    if (m.durationMs <= 0) m.durationMs = mmrDuration(tmp);
                    how = "remux+mmr";
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        // 3) MediaCodec first-frame decode
        if (m.bitmap == null) {
            m.bitmap = codecFrame(file);
            how = "codec";
        }

        if (m.bitmap != null) {
            m.bitmap = scale(m.bitmap);
            if (stem != null) CACHE.put(stem, m.bitmap);
            Log.i(TAG, "thumb ok via " + how + " for " + (stem != null ? stem : file.getName()));
        } else {
            Log.w(TAG, "thumb FAILED (all paths) for " + (stem != null ? stem : file.getName()));
        }
        return m;
    }

    // ── MediaMetadataRetriever ──────────────────────────────────────────

    private static Bitmap mmrFrame(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            return r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static long mmrDuration(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
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

    // ── MediaCodec first-frame decode (fallback) ────────────────────────

    private static Bitmap codecFrame(File file) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int guard = 0;
            while (guard++ < 3000) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    Bitmap bmp = null;
                    if (info.size > 0) {
                        Image img = codec.getOutputImage(outIdx);
                        if (img != null) { bmp = imageToBitmap(img); img.close(); }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (bmp != null) return bmp;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return null;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] nv21 = yuv420ToNv21(image, w, h);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 90, out);
        byte[] jpeg = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private static byte[] yuv420ToNv21(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, pos, width);
            pos += width;
        }

        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixStride = planes[2].getPixelStride();
        int cw = width / 2;
        int ch = height / 2;
        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                nv21[pos++] = vBuf.get(row * vRowStride + col * vPixStride);
                nv21[pos++] = uBuf.get(row * uRowStride + col * uPixStride);
            }
        }
        return nv21;
    }

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
