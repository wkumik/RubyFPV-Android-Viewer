/*
 * RubyFPV Viewer — Android video viewer for RubyFPV ground stations
 *
 * Video decoding architecture inspired by OpenIPC Decoder
 * (https://github.com/OpenIPC/decoder) and Consti10/myMediaCodecPlayer-for-FPV
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
 * Uses the proven Consti10 state machine pattern to extract NAL units from the
 * raw Annex B byte stream arriving in UDP packets. Each complete NAL unit is
 * fed to MediaCodec as a separate input buffer (required by the API).
 */
public class MainActivity extends Activity {
    private static final String TAG = "RubyFPVViewer";
    private static final int UDP_PORT = 5001;
    private static final int STREAM_TIMEOUT_MS = 3000;
    private static final int NALU_MAXLEN = 1024 * 1024;

    private final BlockingQueue<byte[]> nalQueue = new ArrayBlockingQueue<>(128);

    // NAL reassembly state (persists across UDP packets)
    private final byte[] naluBuf = new byte[NALU_MAXLEN];
    private int naluPos = 0;
    private int searchState = 0; // 0-3: counts consecutive zero bytes seen

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
            naluPos = 0;
            searchState = 0;
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

                        parsePacket(pkt.getData(), len);

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
     * State machine NAL extractor (Consti10 pattern).
     *
     * Scans for 4-byte start codes (00 00 00 01) across UDP packet boundaries.
     * When a start code is found, everything accumulated before it is emitted
     * as a complete NAL unit. State persists between calls.
     */
    private void parsePacket(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            // Write byte to NAL buffer
            if (naluPos < NALU_MAXLEN) {
                naluBuf[naluPos++] = data[i];
            } else {
                // Overflow — reset
                naluPos = 0;
                searchState = 0;
                continue;
            }

            // State machine for detecting 00 00 00 01
            switch (searchState) {
                case 0:
                    if (data[i] == 0) searchState = 1;
                    // else stay at 0
                    break;
                case 1:
                    if (data[i] == 0) searchState = 2;
                    else searchState = 0;
                    break;
                case 2:
                    if (data[i] == 0) searchState = 3;
                    else searchState = 0;
                    break;
                case 3:
                    if (data[i] == 1) {
                        // Found start code 00 00 00 01
                        // NAL data is naluBuf[0 .. naluPos-4]
                        int nalLen = naluPos - 4;
                        if (nalLen > 0) {
                            byte[] nal = new byte[nalLen];
                            System.arraycopy(naluBuf, 0, nal, 0, nalLen);
                            if (!nalQueue.offer(nal)) {
                                nalQueue.poll(); // drop oldest
                                nalQueue.offer(nal);
                            }
                            nalsRx++;
                        }
                        // Start new NAL with the start code
                        naluBuf[0] = 0;
                        naluBuf[1] = 0;
                        naluBuf[2] = 0;
                        naluBuf[3] = 1;
                        naluPos = 4;
                    }
                    // Whether 01 or not, reset state
                    // (handles 00 00 00 00 01 correctly — the extra 0
                    //  was accumulated, and we keep scanning)
                    searchState = (data[i] == 0) ? 1 : 0;
                    break;
            }
        }
    }

    // ── Decode Thread ───────────────────────────────────────────────────

    private void decodeThread() {
        Log.i(TAG, "Decode thread started");
        while (running) {
            try {
                byte[] nal = nalQueue.poll(10, TimeUnit.MILLISECONDS);
                if (nal != null) feedDecoder(nal);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Feed one complete NAL unit to MediaCodec.
     * No BUFFER_FLAG_CODEC_CONFIG — let the decoder handle SPS/PPS
     * detection from the stream automatically.
     */
    private void feedDecoder(byte[] nal) {
        if (decoder == null && !createDecoder()) return;

        try {
            int inId = decoder.dequeueInputBuffer(10000);
            if (inId >= 0) {
                ByteBuffer inBuf = decoder.getInputBuffer(inId);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(nal);
                    decoder.queueInputBuffer(inId, 0, nal.length,
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
                naluPos = 0;
                searchState = 0;
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
