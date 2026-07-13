package com.rubyfpv.viewer.recordings;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.InAppMp4Muxer;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remux an onboard HEVC clip from its MPEG-TS container into MP4 so it can be
 * shared to apps that reject {@code .ts} (Instagram, WhatsApp, TikTok, …).
 *
 * <p>The Android framework muxer ({@link android.media.MediaMuxer}) can't demux
 * HEVC-in-TS — it reports zero tracks (see {@link Thumbs}) — so we use media3's
 * {@link Transformer}, which drives the same ExoPlayer {@code TsExtractor} that
 * plays and thumbnails the clip. With no effects applied and a codec the MP4
 * container supports (HEVC), Transformer <i>transmuxes</i> (stream-copy): no
 * decode/encode, so it's fast and lossless — only the container changes.</p>
 *
 * <p>{@link #remux} blocks the calling (background) thread until the export
 * finishes or times out. Transformer must be created and driven from a single
 * {@link android.os.Looper}, so we host it on a private {@link HandlerThread}
 * and bridge completion back with a {@link CountDownLatch}. The MP4 is written
 * to a {@code .part} sibling and renamed into place only on success, so a
 * crash/kill mid-convert never leaves a half-written {@code .mp4} that later
 * looks playable.</p>
 */
public final class Mp4Export {

    /** Generous ceiling; a transmux is I/O-bound, not CPU, so this is rarely hit. */
    private static final long TIMEOUT_MIN = 10;

    private Mp4Export() {}

    /**
     * Remux {@code src} (.ts) into {@code dst} (.mp4), stream-copying the HEVC.
     *
     * @return {@code true} if {@code dst} now holds a complete, playable MP4. On
     *         failure {@code dst} is left absent and the caller should keep
     *         {@code src} as the (still .ts) playable/shareable file.
     */
    public static boolean remux(Context ctx, File src, File dst) {
        final Context app = ctx.getApplicationContext();
        final File part = new File(dst.getAbsolutePath() + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();   // clear any leftover from a previous interrupted run

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final Transformer[] ref = new Transformer[1];

        final HandlerThread ht = new HandlerThread("mp4-export");
        ht.start();
        final Handler h = new Handler(ht.getLooper());

        h.post(() -> {
            Transformer t = new Transformer.Builder(app)
                    // Use media3's in-app MP4 muxer, NOT the Android framework MediaMuxer:
                    // the framework muxer fails to write this onboard HEVC stream
                    // (stop() error -1007 ERROR_MALFORMED). The in-app muxer handles it.
                    .setMuxerFactory(new InAppMp4Muxer.Factory())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition c, ExportResult r) {
                            ok.set(true);
                            done.countDown();
                        }

                        @Override
                        public void onError(Composition c, ExportResult r, ExportException e) {
                            DebugLog.add("mp4 export failed (" + src.getName() + "): " + e.getMessage());
                            done.countDown();
                        }
                    })
                    .build();
            ref[0] = t;
            t.start(MediaItem.fromUri(Uri.fromFile(src)), part.getAbsolutePath());
        });

        boolean finished = false;
        try {
            finished = done.await(TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finished) {
            DebugLog.add("mp4 export timed out for " + src.getName());
            // cancel() must run on the Transformer's own looper; quitSafely() lets
            // this queued message run before the looper stops.
            h.post(() -> { try { if (ref[0] != null) ref[0].cancel(); } catch (Exception ignore) {} });
        }
        ht.quitSafely();

        boolean good = ok.get() && part.exists() && part.length() > 0 && part.renameTo(dst);
        if (!good) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        return good;
    }
}
