/*
 * EX5 Installer - install and mirror the Geely EX5 over Wi-Fi ADB.
 * Copyright (C) 2026 Antony-12s
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.wasak.ex5installer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one car display on the phone and sends taps back. Display 0 = main screen. */
public class ScreenActivity extends Activity implements SurfaceHolder.Callback {
    static final long FRAME_US = 33_333; // nominal 30 fps step
    static final String EXTRA_HOST = "host", EXTRA_PORT = "port", EXTRA_DISPLAY = "display";

    /** SurfaceView that keeps the video's aspect ratio, so the picture is not stretched. */
    public static final class VideoView extends SurfaceView {
        private int vw = 16, vh = 9;

        public VideoView(Context c, AttributeSet a) {
            super(c, a);
        }

        void setVideoSize(int w, int h) {
            vw = w;
            vh = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getSize(widthSpec), maxH = MeasureSpec.getSize(heightSpec);
            double scale = Math.min((double) maxW / vw, (double) maxH / vh);
            setMeasuredDimension((int) Math.round(vw * scale), (int) Math.round(vh * scale));
        }
    }

    private VideoView view;
    private TextView status;
    private Surface surface;
    private MediaCodec codec;
    private Scrcpy scrcpy;
    private Adb adb;
    private int vw, vh;
    private byte[] config;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new VideoView(this, null);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x88000000);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button close = new Button(this);
        close.setText("ปิด");
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        root.addView(close, lp);
        setContentView(root);

        view.setOnTouchListener(this::touched);
    }

    private boolean touched(View v, MotionEvent e) {
        int action;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = Scrcpy.TOUCH_DOWN; break;
            case MotionEvent.ACTION_UP: action = Scrcpy.TOUCH_UP; break;
            case MotionEvent.ACTION_MOVE: action = Scrcpy.TOUCH_MOVE; break;
            default: return false;
        }
        float fx = e.getX() / v.getWidth(), fy = e.getY() / v.getHeight();
        Scrcpy s = scrcpy;
        if (s != null) io.submit(() -> s.touch(action, fx, fy));
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        if (scrcpy == null) connect();
        else synchronized (this) { startCodec(); }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        surface = holder.getSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        releaseCodec();
    }

    private void connect() {
        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, 5566);
        int display = getIntent().getIntExtra(EXTRA_DISPLAY, 0);
        say("กำลังต่อ " + host + ":" + port + " ...");
        new Thread(() -> {
            try {
                adb = Adb.connect(host, port, Keys.get(this), () -> say("รอกด Allow บนจอรถ..."));
                say("ต่อแล้ว: " + adb.model() + " / display " + display);
                scrcpy = Scrcpy.start(adb, getAssets().open("scrcpy-server.jar"), display, true, listener);
            } catch (Exception e) {
                say("ต่อไม่ได้: " + e.getMessage());
                closeAdb();
            }
        }, "screen-connect").start();
    }

    private final Scrcpy.Listener listener = new Scrcpy.Listener() {
        @Override
        public void onVideoSize(int w, int h) {
            vw = w;
            vh = h;
            runOnUiThread(() -> {
                view.setVideoSize(w, h);
                say("ภาพ " + w + "x" + h);
            });
        }

        @Override
        public void onConfig(byte[] annexB) {
            synchronized (ScreenActivity.this) {
                config = annexB;
                releaseCodec();
                startCodec();
            }
        }

        @Override
        public void onFrame(byte[] annexB, long ptsUs) {
            feed(annexB);
        }

        @Override
        public void onEnd(String reason) {
            say("จอหลุด: " + reason);
            closeAdb();
        }
    };

    private synchronized void startCodec() {
        Surface s = surface;
        if (s == null || config == null || vw == 0) return;
        try {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", vw, vh);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(config));
            codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(f, s, null, 0);
            codec.start();
        } catch (IOException | RuntimeException e) {
            say("เปิดตัวถอดภาพไม่ได้: " + e.getMessage());
            releaseCodec();
        }
    }

    /**
     * Runs on the video thread; decodes one access unit and shows whatever is ready.
     * The decoder gets our own frame counter as time, not the device pts: the emulator's codec aborted on the device pts.
     */
    private long framesFed;

    private synchronized void feed(byte[] frame) {
        MediaCodec c = codec;
        if (c == null) return;
        try {
            int in = c.dequeueInputBuffer(20_000);
            if (in >= 0) {
                ByteBuffer buf = c.getInputBuffer(in);
                buf.clear();
                buf.put(frame);
                c.queueInputBuffer(in, 0, frame.length, ++framesFed * FRAME_US, 0);
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int out;
            while ((out = c.dequeueOutputBuffer(info, 0)) >= 0) c.releaseOutputBuffer(out, true);
        } catch (RuntimeException e) {
            say("ถอดภาพพัง: " + e.getMessage());
            releaseCodec();
        }
    }

    private synchronized void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        c.release();
    }

    private void closeAdb() {
        Scrcpy s = scrcpy;
        scrcpy = null;
        io.submit(() -> {
            if (s != null) s.close();
            if (adb != null) adb.close();
        });
    }

    private void say(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    @Override
    protected void onDestroy() {
        closeAdb();
        releaseCodec();
        io.shutdown();
        super.onDestroy();
    }
}
