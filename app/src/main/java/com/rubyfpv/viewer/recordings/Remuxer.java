package com.rubyfpv.viewer.recordings;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Lossless rewrap of an MPEG-TS elementary stream into an MP4 container.
 *
 * <p>Uses the in-platform {@link MediaExtractor} + {@link MediaMuxer} (API&nbsp;24+
 * muxes HEVC) to copy samples verbatim — zero re-encode, bit-identical video.
 * MP4 gives ExoPlayer/VideoView a real seek index (raw .ts has none) and a
 * share-friendly {@code video/mp4} container.</p>
 */
public final class Remuxer {

    private static final String TAG = "Remuxer";

    private Remuxer() {}

    /** @return true if the output MP4 was written successfully. */
    public static boolean remux(File src, File dst) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean ok = false;
        try {
            extractor.setDataSource(src.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            int[] muxIndex = new int[trackCount];
            int maxInput = 1 << 20; // 1 MB floor

            muxer = new MediaMuxer(dst.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            boolean haveVideo = false;
            for (int i = 0; i < trackCount; i++) {
                muxIndex[i] = -1;
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    muxIndex[i] = muxer.addTrack(fmt);
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = Math.max(maxInput,
                                fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                    if (mime.startsWith("video/")) haveVideo = true;
                }
            }
            if (!haveVideo) {
                Log.w(TAG, "No video track in " + src.getName());
                return false;
            }

            ByteBuffer buf = ByteBuffer.allocate(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            muxer.start();

            long lastPts = 0;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) break;

                long pts = extractor.getSampleTime();
                if (pts < 0) pts = lastPts;
                else lastPts = pts;

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                info.flags = (extractor.getSampleFlags()
                        & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

                int dest = muxIndex[track];
                if (dest >= 0) muxer.writeSampleData(dest, buf, info);
                extractor.advance();
            }
            ok = true;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Remux failed: " + e.getMessage());
            ok = false;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
        if (!ok) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        return ok;
    }
}
