/*
 * RubyFPV Viewer — Android video viewer for RubyFPV ground stations
 *
 * Video decoding architecture inspired by OpenIPC Decoder
 * (https://github.com/OpenIPC/decoder) — MIT License
 *
 * Copyright (c) OpenIPC (original decoder architecture)
 * Copyright (c) RubyFPV Viewer contributors
 *
 * MIT License
 */

package com.rubyfpv.viewer;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

/**
 * Receives raw H.264 video stream from a RubyFPV ground station over USB tethering.
 *
 * Ruby sends raw H.264 byte stream (Annex B format with start codes) via UDP to port 5001.
 * We accumulate the stream and split on NAL start codes (00 00 00 01 / 00 00 01),
 * then feed individual NAL units to Android's hardware MediaCodec decoder.
 */
public class MainActivity extends Activity {
    private static final String TAG = "RubyFPVViewer";
    private static final int UDP_PORT = 5001;
    private static final int STREAM_TIMEOUT_MS = 3000;

    private MediaCodec decoder;
    private SurfaceView surfaceView;
    private TextView statusText;
    private TextView statsText;

    private volatile boolean running;
    private volatile boolean receiving;
    private volatile long lastFrameTime;
    private int lastWidth;
    private int lastHeight;

    // Stats
    private volatile long bytesReceived;
    private volatile long packetsReceived;
    private volatile long nalCount;
    private long lastBytesReceived;
    private long lastPacketsReceived;
    private long lastNalCount;

    // Stream assembly buffer: accumulates raw H.264 byte stream across UDP packets
    private final byte[] accumBuffer = new byte[4 * 1024 * 1024];
    private int accumPos = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.video_surface);
        surfaceView.setKeepScreenOn(true);

        statusText = findViewById(R.id.text_status);
        statusText.setTextColor(Color.LTGRAY);

        statsText = findViewById(R.id.text_stats);

        hideSystemUI();

        findViewById(R.id.root).setOnClickListener(v -> {
            if (statsText.getVisibility() == View.VISIBLE) {
                statsText.setVisibility(View.GONE);
            } else {
                statsText.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!running) {
            running = true;
            statusText.setText("Waiting for RubyFPV stream on port " + UDP_PORT + "...\nEnable USB tethering on this device");
            statusText.setVisibility(View.VISIBLE);
            startThreads();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        receiving = false;
        closeDecoder();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ── UDP Receiver + Decoder (single thread) ──────────────────────────

    /**
     * Receives UDP packets and decodes video in a single thread.
     * Accumulates raw H.264 byte stream, splits on NAL start codes,
     * and feeds individual NAL units directly to MediaCodec.
     */
    private void receiverThread() {
        Log.i(TAG, "Receiver started on port " + UDP_PORT);

        while (running) {
            try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
                socket.setSoTimeout(1000);
                socket.setReceiveBufferSize(512 * 1024);
                byte[] buf = new byte[65536];

                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);

                        int len = packet.getLength();
                        if (len <= 0) continue;

                        if (!receiving) {
                            receiving = true;
                            runOnUiThread(() -> statusText.setText("Receiving stream..."));
                        }

                        lastFrameTime = SystemClock.elapsedRealtime();
                        bytesReceived += len;
                        packetsReceived++;

                        // Append to accumulation buffer
                        if (accumPos + len > accumBuffer.length) {
                            // Buffer full, reset
                            Log.w(TAG, "Accum buffer full, resetting");
                            accumPos = 0;
                        }
                        System.arraycopy(packet.getData(), 0, accumBuffer, accumPos, len);
                        accumPos += len;

                        // Extract and decode complete NAL units
                        extractAndDecodeNals();

                    } catch (java.net.SocketTimeoutException e) {
                        // Normal timeout
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Receiver error: " + e.getMessage());
                SystemClock.sleep(500);
            }
        }

        Log.i(TAG, "Receiver stopped");
    }

    /**
     * Scan accumulation buffer for complete NAL units and decode them.
     * A NAL unit starts at a start code and ends just before the next start code.
     */
    private void extractAndDecodeNals() {
        int firstStart = findStartCode(accumBuffer, 0, accumPos);
        if (firstStart < 0) return;

        int pos = firstStart;
        while (pos < accumPos) {
            // Find the start of this NAL (skip past the start code)
            int scLen = startCodeLength(accumBuffer, pos, accumPos);
            if (scLen == 0) break;

            int nalDataStart = pos; // Include start code in NAL data

            // Find the next start code (= end of this NAL)
            int nextStart = findStartCode(accumBuffer, pos + scLen, accumPos);

            if (nextStart < 0) {
                // No next start code found — NAL is incomplete, keep it in buffer
                break;
            }

            // We have a complete NAL from nalDataStart to nextStart
            int nalLen = nextStart - nalDataStart;
            if (nalLen > 0) {
                decodeNal(accumBuffer, nalDataStart, nalLen);
                nalCount++;
            }

            pos = nextStart;
        }

        // Shift remaining data to start of buffer
        if (pos > 0 && pos < accumPos) {
            int remaining = accumPos - pos;
            System.arraycopy(accumBuffer, pos, accumBuffer, 0, remaining);
            accumPos = remaining;
        } else if (pos >= accumPos) {
            accumPos = 0;
        }
    }

    /**
     * Find the position of the next start code (00 00 01 or 00 00 00 01)
     * starting from 'from' within the buffer.
     */
    private int findStartCode(byte[] buf, int from, int limit) {
        for (int i = from; i < limit - 2; i++) {
            if (buf[i] == 0x00 && buf[i + 1] == 0x00) {
                if (buf[i + 2] == 0x01) {
                    // 3-byte start code, check if it's actually a 4-byte one
                    if (i > 0 && buf[i - 1] == 0x00) {
                        return i - 1; // 4-byte start code starts one byte earlier
                    }
                    return i;
                }
                if (i + 3 < limit && buf[i + 2] == 0x00 && buf[i + 3] == 0x01) {
                    return i; // 4-byte start code
                }
            }
        }
        return -1;
    }

    /**
     * Returns the length of the start code at the given position (3 or 4), or 0 if none.
     */
    private int startCodeLength(byte[] buf, int pos, int limit) {
        if (pos + 3 < limit && buf[pos] == 0x00 && buf[pos + 1] == 0x00
                && buf[pos + 2] == 0x00 && buf[pos + 3] == 0x01) {
            return 4;
        }
        if (pos + 2 < limit && buf[pos] == 0x00 && buf[pos + 1] == 0x00
                && buf[pos + 2] == 0x01) {
            return 3;
        }
        return 0;
    }

    // ── Decoder ─────────────────────────────────────────────────────────

    private void decodeNal(byte[] data, int offset, int length) {
        if (decoder == null) {
            if (!createDecoder()) return;
        }

        // Determine NAL type (byte after start code)
        int headerOffset = offset + startCodeLength(data, offset, offset + length);
        if (headerOffset >= offset + length) return;

        int nalType = data[headerOffset] & 0x1F;
        int flag = 0;
        if (nalType == 7 || nalType == 8) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            Log.d(TAG, "Codec config NAL type=" + nalType + " len=" + length);
        }

        try {
            int inputId = decoder.dequeueInputBuffer(10000);
            if (inputId >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputId);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, offset, length);
                    decoder.queueInputBuffer(inputId, 0,
                            length, System.nanoTime() / 1000, flag);
                }
            }

            // Drain all available output
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputId;
            while ((outputId = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                MediaFormat format = decoder.getOutputFormat(outputId);
                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);

                if (lastWidth != width || lastHeight != height) {
                    lastWidth = width;
                    lastHeight = height;
                    updateResolution(width, height);
                }

                decoder.releaseOutputBuffer(outputId, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Decode error: " + e.getMessage());
            closeDecoder();
        }
    }

    private boolean createDecoder() {
        Surface surface = surfaceView.getHolder().getSurface();
        if (!surface.isValid()) return false;

        try {
            Log.i(TAG, "Creating H.264 decoder");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

            MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(format, surface, null, 0);
            codec.start();
            decoder = codec;

            runOnUiThread(() -> statusText.setVisibility(View.GONE));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot create decoder: " + e.getMessage());
            return false;
        }
    }

    private void closeDecoder() {
        if (decoder != null) {
            Log.i(TAG, "Closing decoder");
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close error");
            }
            decoder = null;
        }
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) return;

        int surfaceH = surfaceView.getHeight();
        int surfaceW = (int) ((float) surfaceH / height * width);
        Log.d(TAG, "Resolution: " + width + "x" + height + " -> " + surfaceW + "x" + surfaceH);

        runOnUiThread(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surfaceW, surfaceH);
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
        });
    }

    // ── Watchdog ────────────────────────────────────────────────────────

    private void watchdogThread() {
        while (running) {
            if (receiving) {
                if (SystemClock.elapsedRealtime() - lastFrameTime > STREAM_TIMEOUT_MS) {
                    Log.w(TAG, "Stream timeout");
                    receiving = false;
                    closeDecoder();
                    accumPos = 0;

                    runOnUiThread(() -> {
                        surfaceView.setVisibility(View.GONE);
                        surfaceView.setVisibility(View.VISIBLE);
                        statusText.setText("Stream lost. Waiting for RubyFPV...");
                        statusText.setVisibility(View.VISIBLE);
                    });
                }

                // Update stats (per-second rates)
                long bpsNow = (bytesReceived - lastBytesReceived) * 8;
                long pktsNow = packetsReceived - lastPacketsReceived;
                long nalsNow = nalCount - lastNalCount;
                lastBytesReceived = bytesReceived;
                lastPacketsReceived = packetsReceived;
                lastNalCount = nalCount;
                String stats = String.format("%.1f Mbps | %d pkt/s | %d NAL/s",
                        bpsNow / 1_000_000.0, pktsNow, nalsNow);
                runOnUiThread(() -> statsText.setText(stats));
            }

            SystemClock.sleep(1000);
        }
    }

    // ── Thread Management ───────────────────────────────────────────────

    private void startThreads() {
        Log.i(TAG, "Starting threads");
        new Thread(this::receiverThread, "receiver").start();
        new Thread(this::watchdogThread, "watchdog").start();
    }
}
