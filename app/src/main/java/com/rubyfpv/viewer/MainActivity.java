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

import android.annotation.SuppressLint;
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
 * Ruby sends raw H.264 NAL unit data (no RTP framing) via UDP to port 5001.
 * The phone connects via USB tethering, appearing as a network interface (usb0)
 * on the Ruby station. Ruby discovers the phone's IP and sends video data as
 * UDP datagrams containing raw H.264 byte stream chunks.
 *
 * We reassemble NAL units by scanning for start codes (0x00 0x00 0x00 0x01),
 * then feed complete NAL units to Android's hardware MediaCodec decoder.
 */
public class MainActivity extends Activity {
    private static final String TAG = "RubyFPVViewer";
    private static final int UDP_PORT = 5001;
    private static final int UDP_BUFFER_SIZE = 65536;
    private static final int STREAM_TIMEOUT_MS = 3000;
    private static final int NAL_BUFFER_SIZE = 2 * 1024 * 1024;

    private final BlockingQueue<NalUnit> nalQueue = new ArrayBlockingQueue<>(64);
    private final byte[] streamBuffer = new byte[NAL_BUFFER_SIZE];
    private int streamBufferPos = 0;

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
    private volatile long nalUnitsDecoded;
    private long lastBytesReceived;
    private long lastPacketsReceived;
    private long lastNalUnitsDecoded;

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

        // Toggle stats on tap
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

    // ── UDP Receiver ────────────────────────────────────────────────────

    /**
     * Ruby sends raw H.264 byte stream in UDP chunks (default 1024 bytes).
     * The chunks are NOT aligned to NAL boundaries — they're just sequential
     * slices of the raw H.264 bitstream. We accumulate data and scan for
     * start codes (0x00 0x00 0x00 0x01) to extract complete NAL units.
     */
    private void udpReceiverThread() {
        Log.i(TAG, "UDP receiver started on port " + UDP_PORT);

        while (running) {
            try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
                socket.setSoTimeout(1000);
                socket.setReceiveBufferSize(512 * 1024);
                byte[] buf = new byte[UDP_BUFFER_SIZE];

                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);

                        int len = packet.getLength();
                        if (len <= 0) continue;

                        if (!receiving) {
                            receiving = true;
                            runOnUiThread(() -> {
                                statusText.setText("Receiving stream...");
                            });
                        }

                        lastFrameTime = SystemClock.elapsedRealtime();
                        bytesReceived += len;
                        packetsReceived++;

                        feedStreamData(packet.getData(), 0, len);

                    } catch (java.net.SocketTimeoutException e) {
                        // Normal timeout, just loop
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "UDP receiver error: " + e.getMessage());
                SystemClock.sleep(500);
            }
        }

        Log.i(TAG, "UDP receiver stopped");
    }

    // Tracks consecutive zero bytes for start code detection
    private int zeroCount = 0;
    private boolean foundFirstStartCode = false;

    /**
     * Feed raw H.264 byte stream data into the NAL unit parser.
     * Scans for start codes (0x00 0x00 0x01 or 0x00 0x00 0x00 0x01)
     * and emits complete NAL units to the decode queue.
     */
    private void feedStreamData(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = data[i];

            if (b == 0x00) {
                zeroCount++;
                if (streamBufferPos < NAL_BUFFER_SIZE) {
                    streamBuffer[streamBufferPos++] = b;
                }
                continue;
            }

            if (b == 0x01 && zeroCount >= 2) {
                // Found start code (00 00 01 or 00 00 00 01)
                // Everything before the zero bytes is the previous NAL unit
                int nalEnd = streamBufferPos - zeroCount;

                if (foundFirstStartCode && nalEnd > 0) {
                    // Emit the previous NAL unit (with its start code prefix)
                    byte[] nal = new byte[nalEnd];
                    System.arraycopy(streamBuffer, 0, nal, 0, nalEnd);
                    if (nal.length > 4) {
                        try {
                            nalQueue.offer(new NalUnit(nal, nalEnd), 5, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Log.w(TAG, "NAL queue full, dropping");
                        }
                    }
                }

                foundFirstStartCode = true;

                // Start new NAL with 4-byte start code
                streamBuffer[0] = 0x00;
                streamBuffer[1] = 0x00;
                streamBuffer[2] = 0x00;
                streamBuffer[3] = 0x01;
                streamBufferPos = 4;
                zeroCount = 0;
                continue;
            }

            // Regular byte
            zeroCount = 0;
            if (streamBufferPos < NAL_BUFFER_SIZE) {
                streamBuffer[streamBufferPos++] = b;
            } else {
                Log.w(TAG, "NAL buffer overflow, resetting");
                streamBufferPos = 0;
                foundFirstStartCode = false;
            }
        }
    }

    // ── Decoder ─────────────────────────────────────────────────────────

    /**
     * Decode thread: pulls NAL units from the queue and feeds them to MediaCodec.
     */
    private void decodeThread() {
        Log.i(TAG, "Decode thread started");

        while (running) {
            try {
                NalUnit nal = nalQueue.poll(10, TimeUnit.MILLISECONDS);
                if (nal != null) {
                    decodeNalUnit(nal);
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Decode interrupted");
            }
        }

        Log.i(TAG, "Decode thread stopped");
    }

    private void decodeNalUnit(NalUnit nal) {
        if (decoder == null) {
            if (!createDecoder()) return;
        }

        lastFrameTime = SystemClock.elapsedRealtime();
        nalUnitsDecoded++;

        // Determine NAL type for H.264
        // Start code is 0x00 0x00 0x00 0x01, NAL header is next byte
        int nalType = 0;
        if (nal.length > 4) {
            nalType = nal.data[4] & 0x1F; // H.264 NAL unit type
        }

        int flag = 0;
        // SPS (7), PPS (8) are codec config
        if (nalType == 7 || nalType == 8) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        try {
            int inputId = decoder.dequeueInputBuffer(5000);
            if (inputId >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputId);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(nal.data, 0, nal.length);
                    decoder.queueInputBuffer(inputId, 0,
                            nal.length, System.nanoTime() / 1000, flag);
                }
            }

            // Drain all available output buffers
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
            // Low latency hints
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

            MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(format, surface, null, 0);
            codec.start();
            decoder = codec;

            runOnUiThread(() -> {
                statusText.setVisibility(View.GONE);
            });

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

    // ── Watchdog & Stats ────────────────────────────────────────────────

    private void watchdogThread() {
        while (running) {
            if (receiving) {
                if (SystemClock.elapsedRealtime() - lastFrameTime > STREAM_TIMEOUT_MS) {
                    Log.w(TAG, "Stream timeout — no data for " + STREAM_TIMEOUT_MS + "ms");
                    receiving = false;
                    closeDecoder();
                    streamBufferPos = 0;
                    nalQueue.clear();

                    runOnUiThread(() -> {
                        surfaceView.setVisibility(View.GONE);
                        surfaceView.setVisibility(View.VISIBLE);
                        statusText.setText("Stream lost. Waiting for RubyFPV...");
                        statusText.setVisibility(View.VISIBLE);
                    });
                }
            }

            // Update stats overlay (per-second rates)
            if (receiving) {
                long bpsNow = (bytesReceived - lastBytesReceived) * 8;
                long pktsNow = packetsReceived - lastPacketsReceived;
                long nalsNow = nalUnitsDecoded - lastNalUnitsDecoded;
                lastBytesReceived = bytesReceived;
                lastPacketsReceived = packetsReceived;
                lastNalUnitsDecoded = nalUnitsDecoded;
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

        new Thread(this::udpReceiverThread, "udp-receiver").start();
        new Thread(this::decodeThread, "decoder").start();
        new Thread(this::watchdogThread, "watchdog").start();
    }

    // ── Data ────────────────────────────────────────────────────────────

    private record NalUnit(byte[] data, int length) {}
}
