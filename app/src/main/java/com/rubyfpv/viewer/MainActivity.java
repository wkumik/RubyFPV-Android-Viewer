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

public class MainActivity extends Activity {
    private static final String TAG = "RubyFPVViewer";
    private static final int UDP_PORT = 5001;
    private static final int STREAM_TIMEOUT_MS = 3000;
    private static final int BUF_SIZE = 2 * 1024 * 1024;

    private final BlockingQueue<byte[]> nalQueue = new ArrayBlockingQueue<>(128);
    private final byte[] streamBuf = new byte[BUF_SIZE];
    private int streamPos = 0;

    private MediaCodec decoder;
    private SurfaceView surfaceView;
    private TextView statusText;
    private TextView statsText;

    private volatile boolean running;
    private volatile boolean receiving;
    private volatile long lastFrameTime;
    private int lastWidth;
    private int lastHeight;

    private volatile long bytesRx;
    private volatile long pktsRx;
    private volatile long nalsRx;
    private long prevBytes, prevPkts, prevNals;

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

    // ── UDP Receive Thread ──────────────────────────────────────────────

    private void udpThread() {
        Log.i(TAG, "UDP started on port " + UDP_PORT);
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
                        bytesRx += len;
                        pktsRx++;

                        processStreamData(pkt.getData(), len);

                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "UDP error: " + e.getMessage());
                SystemClock.sleep(500);
            }
        }
    }

    /**
     * Accumulate raw H.264 bytes and extract NAL units.
     *
     * We write each byte to streamBuf. After writing, we check if the last
     * 3 or 4 bytes form a start code (00 00 01 or 00 00 00 01).
     * When found, everything before the start code is a complete NAL unit.
     */
    private void processStreamData(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            if (streamPos >= BUF_SIZE) {
                Log.w(TAG, "Buffer overflow, resetting");
                streamPos = 0;
            }

            // Write byte first
            streamBuf[streamPos] = data[i];
            streamPos++;

            // Check if we just completed a start code
            // 4-byte: 00 00 00 01
            if (streamPos >= 4
                    && streamBuf[streamPos - 4] == 0
                    && streamBuf[streamPos - 3] == 0
                    && streamBuf[streamPos - 2] == 0
                    && streamBuf[streamPos - 1] == 1) {

                int nalEnd = streamPos - 4;
                if (nalEnd > 0) {
                    emitNal(nalEnd);
                }
                // Keep start code at beginning of buffer
                streamBuf[0] = 0;
                streamBuf[1] = 0;
                streamBuf[2] = 0;
                streamBuf[3] = 1;
                streamPos = 4;
                continue;
            }

            // 3-byte: 00 00 01 (but NOT preceded by another 00, which would be 4-byte)
            if (streamPos >= 3
                    && streamBuf[streamPos - 3] == 0
                    && streamBuf[streamPos - 2] == 0
                    && streamBuf[streamPos - 1] == 1
                    && (streamPos < 4 || streamBuf[streamPos - 4] != 0)) {

                int nalEnd = streamPos - 3;
                if (nalEnd > 0) {
                    emitNal(nalEnd);
                }
                // Normalize to 4-byte start code
                streamBuf[0] = 0;
                streamBuf[1] = 0;
                streamBuf[2] = 0;
                streamBuf[3] = 1;
                streamPos = 4;
            }
        }
    }

    private void emitNal(int nalEnd) {
        // streamBuf[0..nalEnd) is a complete NAL (with start code prefix from previous round)
        if (nalEnd <= 4) return; // Too short — just a start code with no data

        byte[] nal = new byte[nalEnd];
        System.arraycopy(streamBuf, 0, nal, 0, nalEnd);
        nalsRx++;

        if (!nalQueue.offer(nal)) {
            // Queue full, drop oldest to make room
            nalQueue.poll();
            nalQueue.offer(nal);
        }
    }

    // ── Decode Thread ───────────────────────────────────────────────────

    private void decodeThread() {
        Log.i(TAG, "Decode thread started");
        while (running) {
            try {
                byte[] nal = nalQueue.poll(10, TimeUnit.MILLISECONDS);
                if (nal != null) decodeNal(nal);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void decodeNal(byte[] nal) {
        if (decoder == null && !createDecoder()) return;

        // Find NAL type: byte after the start code (00 00 00 01 XX or 00 00 01 XX)
        int hdr = 4; // We normalize to 4-byte start codes
        if (hdr >= nal.length) return;

        int nalType = nal[hdr] & 0x1F;
        int flag = 0;
        if (nalType == 7 || nalType == 8) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            Log.d(TAG, "Config NAL type=" + nalType + " len=" + nal.length);
        }

        try {
            int inId = decoder.dequeueInputBuffer(10000);
            if (inId >= 0) {
                ByteBuffer inBuf = decoder.getInputBuffer(inId);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(nal);
                    decoder.queueInputBuffer(inId, 0, nal.length,
                            System.nanoTime() / 1000, flag);
                }
            }

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
                streamPos = 0;
                nalQueue.clear();
                runOnUiThread(() -> {
                    surfaceView.setVisibility(View.GONE);
                    surfaceView.setVisibility(View.VISIBLE);
                    statusText.setText("Stream lost. Waiting for RubyFPV...");
                    statusText.setVisibility(View.VISIBLE);
                });
            }

            if (receiving) {
                long db = bytesRx - prevBytes;
                long dp = pktsRx - prevPkts;
                long dn = nalsRx - prevNals;
                prevBytes = bytesRx;
                prevPkts = pktsRx;
                prevNals = nalsRx;
                String s = String.format("%.1f Mbps | %d pkt/s | %d NAL/s",
                        db * 8 / 1_000_000.0, dp, dn);
                runOnUiThread(() -> statsText.setText(s));
            }
            SystemClock.sleep(1000);
        }
    }
}
