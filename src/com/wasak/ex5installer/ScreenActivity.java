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
import android.content.res.Configuration;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    private LinearLayout root, bar;
    private FrameLayout videoArea;
    private TextView status;
    private Surface surface;
    private MediaCodec codec;
    private Scrcpy scrcpy;
    private Adb adb;
    private int vw, vh;
    private byte[] config;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Main-thread handler and the pending auto-hide task for the status pill. */
    private Handler ui;
    private Runnable hidePill;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // No system title bar; sticky-immersive fullscreen so the whole screen is the car view.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        ui = new Handler(getMainLooper());

        // Root: full-bleed themed background. Video area + control bar side by side, so the
        // buttons never cover the car picture: bar at the bottom in portrait, on the right in landscape.
        root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);

        // Video area: the aspect-correct picture is letterboxed and centred inside whatever space is left.
        videoArea = new FrameLayout(this);
        view = new VideoView(this, null);
        view.getHolder().addCallback(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.gravity = Gravity.CENTER;
        videoArea.addView(view, vlp);

        // Status pill: rounded translucent chip, top-centre of the video area, auto-hiding.
        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setBackground(Ui.roundRect(this, 0xCC181D25, Ui.RADIUS_PILL, 0));
        int px = Ui.dp(this, Ui.S3), py = Ui.dp(this, Ui.S2);
        status.setPadding(px, py, px, py);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Ui.dp(this, Ui.S4);
        videoArea.addView(status, slp);
        root.addView(videoArea);

        // Control bar: the car has no Back button, so Home/Back are sent as scrcpy key events.
        bar = new LinearLayout(this);
        int pad = Ui.dp(this, Ui.S2);
        bar.setPadding(pad, pad, pad, pad);
        bar.addView(barButton("Home", v -> sendKey(Scrcpy.KEY_HOME)));
        bar.addView(barButton("ย้อนกลับ", v -> sendKey(Scrcpy.KEY_BACK)));
        bar.addView(barButton("ปิด", v -> finish()));
        root.addView(bar);

        applyOrientation(getResources().getConfiguration().orientation);
        setContentView(root);

        view.setOnTouchListener(this::touched);
    }

    private Button barButton(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        Ui.secondary(b); // 52dp min height
        int min = Ui.dp(this, 48);
        b.setMinWidth(min);
        b.setMinimumWidth(min);
        b.setMinHeight(Math.max(min, Ui.dp(this, 52)));
        b.setOnClickListener(click);
        return b;
    }

    private void sendKey(int keycode) {
        Scrcpy s = scrcpy;
        if (s != null) io.submit(() -> s.key(keycode));
    }

    /**
     * Portrait: video on top, bar row below. Landscape: video left, bar column on the right.
     * Only orientations and LayoutParams change: the VideoView keeps its parent, so its surface survives.
     */
    private void applyOrientation(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        int gap = Ui.dp(this, Ui.S2);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.updateViewLayout(videoArea, landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bar.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        root.updateViewLayout(bar, landscape
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < bar.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = landscape
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                if (landscape) lp.topMargin = gap;
                else lp.leftMargin = gap;
            }
            bar.updateViewLayout(bar.getChildAt(i), lp);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) applyOrientation(newConfig.orientation);
        goImmersive();
    }

    /** Hide the system bars, sticky so a swipe only reveals them briefly. */
    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
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

    private boolean slowWarned;

    private synchronized void feed(byte[] frame) {
        MediaCodec c = codec;
        if (c == null) return;
        try {
            // Never drop an access unit: every later P-frame references it, a gap shows as blocks
            // until the next keyframe (seen on the real car). Drain output to free input slots instead.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 1000;
            int in = -1;
            while (in < 0 && System.currentTimeMillis() < deadline) {
                int out;
                while ((out = c.dequeueOutputBuffer(info, 0)) >= 0) c.releaseOutputBuffer(out, true);
                in = c.dequeueInputBuffer(10_000);
            }
            if (in >= 0) {
                ByteBuffer buf = c.getInputBuffer(in);
                buf.clear();
                buf.put(frame);
                c.queueInputBuffer(in, 0, frame.length, ++framesFed * FRAME_US, 0);
            } else if (!slowWarned) {
                slowWarned = true;
                say("ถอดภาพไม่ทัน");
            }
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
        Adb a = adb;
        Runnable close = () -> {
            if (s != null) s.close();
            if (a != null) a.close();
        };
        // The stream can end after onDestroy shut the executor down: close on a plain thread then.
        try {
            io.submit(close);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            new Thread(close, "screen-close").start();
        }
    }

    /**
     * Updates the status pill from any thread. Normal/progress messages fade out after ~3s;
     * sticky messages (errors, drops, still-connecting) stay until the next say().
     */
    private void say(String text) {
        runOnUiThread(() -> showPill(text));
    }

    /** Must run on the UI thread. Guards against being called before views exist. */
    private void showPill(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        status.setAlpha(1f);
        if (ui != null) {
            if (hidePill != null) ui.removeCallbacks(hidePill);
            if (!isSticky(text)) {
                hidePill = () -> {
                    if (status != null) status.setVisibility(View.GONE);
                };
                ui.postDelayed(hidePill, 3000);
            }
        }
    }

    /** Errors / drops / in-progress states keep the pill on screen. */
    private boolean isSticky(String text) {
        if (text == null) return false;
        return text.contains("ต่อไม่ได้") || text.contains("ผิดพลาด") || text.contains("หลุด")
                || text.contains("พัง") || text.contains("กำลังต่อ") || text.contains("รอกด");
    }

    @Override
    protected void onDestroy() {
        closeAdb();
        releaseCodec();
        io.shutdown();
        super.onDestroy();
    }
}
