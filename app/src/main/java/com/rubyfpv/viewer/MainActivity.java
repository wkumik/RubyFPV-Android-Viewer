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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Receives raw H.264 video stream from a RubyFPV ground station over USB tethering.
 *
 * Simplest possible approach: receive UDP packets containing raw H.264 Annex B
 * byte stream, and feed each packet directly to MediaCodec as a chunk.
 * The hardware decoder handles NAL boundary detection internally.
 */
public class MainActivity extends Activity {
    private static final String TAG = "RubyFPVViewer";
    private static final int UDP_PORT = 5001;
    private static final int STREAM_TIMEOUT_MS = 3000;

    private final BlockingQueue<byte[]> packetQueue = new ArrayBlockingQueue<>(256);

    private MediaCodec decoder;
    private SurfaceView surfaceView;
    private TextView statusText;
    private TextView statsText;

    private volatile boolean running;
    private volatile boolean receiving;
    private volatile long lastFrameTime;
    private int lastWidth;
    private int lastHeight;

    private volatile long bytesReceived;
    private volatile long packetsReceived;
    private long prevBytes;
    private long prevPkts;

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

        findViewById(R.id.root).setOnClickListener(v ->
                statsText.setVisibility(
                        statsText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!running) {
            running = true;
            statusText.setText("Waiting for RubyFPV stream on port " + UDP_PORT
                    + "...\nEnable USB tethering on this device");
            statusText.setVisibility(View.VISIBLE);
            new Thread(this::udpThread, "udp").start();
            new Thread(this::decodeThread, "decode").start();
            new Thread(this::watchdogThread, "watchdog").start();
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

    // ── UDP Receiver ────────────────────────────────────────────────────

    private void udpThread() {
        Log.i(TAG, "UDP thread started on port " + UDP_PORT);
        while (running) {
            try (DatagramSocket sock = new DatagramSocket(UDP_PORT)) {
                sock.setSoTimeout(1000);
                sock.setReceiveBufferSize(1024 * 1024);
                byte[] buf = new byte[65536];

                while (running) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        int len = pkt.getLength();
                        if (len <= 0) continue;

                        if (!receiving) {
                            receiving = true;
                            runOnUiThread(() -> statusText.setText("Receiving..."));
                        }
                        lastFrameTime = SystemClock.elapsedRealtime();
                        bytesReceived += len;
                        packetsReceived++;

                        // Copy packet data and queue it
                        byte[] copy = new byte[len];
                        System.arraycopy(pkt.getData(), 0, copy, 0, len);
                        if (!packetQueue.offer(copy)) {
                            Log.w(TAG, "Packet queue full, dropping");
                        }

                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "UDP error: " + e.getMessage());
                SystemClock.sleep(500);
            }
        }
    }

    // ── Decode Thread ───────────────────────────────────────────────────

    private void decodeThread() {
        Log.i(TAG, "Decode thread started");
        while (running) {
            try {
                byte[] data = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                if (data != null) {
                    feedDecoder(data);
                }
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Feed raw H.264 byte stream chunk directly to MediaCodec.
     * No NAL splitting — let the hardware decoder handle it.
     */
    private void feedDecoder(byte[] data) {
        if (decoder == null && !createDecoder()) return;

        try {
            int inId = decoder.dequeueInputBuffer(5000);
            if (inId >= 0) {
                ByteBuffer inBuf = decoder.getInputBuffer(inId);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(data);
                    decoder.queueInputBuffer(inId, 0, data.length,
                            System.nanoTime() / 1000, 0);
                }
            }

            // Drain all output
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outId;
            while ((outId = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                MediaFormat fmt = decoder.getOutputFormat(outId);
                int w = fmt.getInteger(MediaFormat.KEY_WIDTH);
                int h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                if (lastWidth != w || lastHeight != h) {
                    lastWidth = w;
                    lastHeight = h;
                    updateResolution(w, h);
                }
                decoder.releaseOutputBuffer(outId, true);
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
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720);

            MediaCodec mc = MediaCodec.createDecoderByType("video/avc");
            mc.configure(fmt, surface, null, 0);
            mc.start();
            decoder = mc;

            Log.i(TAG, "Decoder created");
            runOnUiThread(() -> statusText.setVisibility(View.GONE));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot create decoder: " + e.getMessage());
            return false;
        }
    }

    private void closeDecoder() {
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
    }

    private void updateResolution(int w, int h) {
        if (w < 64 || h < 64) return;
        int sH = surfaceView.getHeight();
        int sW = (int) ((float) sH / h * w);
        runOnUiThread(() -> {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(sW, sH);
            p.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(p);
        });
    }

    // ── Watchdog ────────────────────────────────────────────────────────

    private void watchdogThread() {
        while (running) {
            if (receiving && SystemClock.elapsedRealtime() - lastFrameTime > STREAM_TIMEOUT_MS) {
                receiving = false;
                closeDecoder();
                packetQueue.clear();
                runOnUiThread(() -> {
                    surfaceView.setVisibility(View.GONE);
                    surfaceView.setVisibility(View.VISIBLE);
                    statusText.setText("Stream lost. Waiting for RubyFPV...");
                    statusText.setVisibility(View.VISIBLE);
                });
            }

            if (receiving) {
                long db = bytesReceived - prevBytes;
                long dp = packetsReceived - prevPkts;
                prevBytes = bytesReceived;
                prevPkts = packetsReceived;
                String s = String.format("%.1f Mbps | %d pkt/s", db * 8 / 1_000_000.0, dp);
                runOnUiThread(() -> statsText.setText(s));
            }

            SystemClock.sleep(1000);
        }
    }
}
