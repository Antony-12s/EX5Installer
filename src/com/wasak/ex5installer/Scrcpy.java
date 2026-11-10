package com.wasak.ex5installer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * scrcpy 5.0.1 client over the Adb channels (mirrors ex5viewer/scrcpy.py, which runs on the car).
 * Video: one H.264 stream of one display. Control: INJECT_TOUCH_EVENT only. No port forward:
 * the server's localabstract socket is opened as an adb channel directly.
 */
public final class Scrcpy implements Closeable {
    public static final String VERSION = "5.0.1"; // must equal the bundled server jar, the server refuses others
    static final int H264 = 0x68323634;
    static final long CONFIG_FLAG = 1L << 62;     // media packet: SPS/PPS
    static final long PTS_MASK = CONFIG_FLAG - 1;
    static final long POINTER_FINGER = 0xFFFFFFFFFFFFFFFEL; // generic finger (-2): a touch, not a mouse
    public static final int TOUCH_DOWN = 0, TOUCH_UP = 1, TOUCH_MOVE = 2;
    // Longest side sent to the phone: smaller frames fit Wi-Fi and cheap hardware decoders (2560 px crashed one).
    static final int MAX_SIZE = 1280;

    public interface Listener {
        void onVideoSize(int width, int height);
        void onConfig(byte[] annexB);                 // SPS/PPS, send to the decoder before frames
        void onFrame(byte[] annexB, long ptsUs);
        void onEnd(String reason);
    }

    private final Adb adb;
    private final Listener listener;
    private final String scid;
    private Adb.Channel shell, video, control;
    private volatile boolean closed;
    private volatile int width, height;

    private Scrcpy(Adb adb, Listener listener) {
        this.adb = adb;
        this.listener = listener;
        this.scid = String.format("%08x", new Random().nextInt(0x7fffffff) + 1);
    }

    /** Starts the server on displayId and begins streaming. jar = scrcpy-server.jar contents. */
    public static Scrcpy start(Adb adb, InputStream jar, int displayId, boolean withControl, Listener listener) throws IOException {
        Scrcpy s = new Scrcpy(adb, listener);
        try {
            s.launch(jar, displayId, withControl);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        return s;
    }

    private void launch(InputStream jar, int displayId, boolean withControl) throws IOException {
        String remoteJar = "/data/local/tmp/ex5-scrcpy-" + scid + ".jar";
        adb.push(jar, remoteJar, null);
        String args = String.join(" ",
                "scid=" + scid, "log_level=warn", "audio=false", "control=" + withControl,
                "display_id=" + displayId, "max_fps=30", "max_size=" + MAX_SIZE, "tunnel_forward=true",
                "clipboard_autosync=false", "video_codec=h264");
        shell = adb.open("shell:CLASSPATH=" + remoteJar + " app_process / com.genymobile.scrcpy.Server " + VERSION + " " + args);
        Adb.Channel log = shell;
        Thread drain = new Thread(() -> {
            try {
                while (log.next(Long.MAX_VALUE) != null) {
                    // server log lines; the stream must still be read or the device side stalls
                }
            } catch (IOException ignored) {
                // channel closed
            }
        }, "scrcpy-log");
        drain.setDaemon(true);
        drain.start();

        String socket = "localabstract:scrcpy_" + scid;
        long deadline = System.currentTimeMillis() + 30_000; // the car's CPU can be busy at start
        video = connectVideo(socket, deadline);
        if (withControl) control = connect(socket, deadline);
        video.readExact(64, 5000); // device name, unused
        int codec = ByteBuffer.wrap(video.readExact(4, 5000)).getInt();
        if (codec != H264) throw new IOException("unexpected codec " + Integer.toHexString(codec));

        Thread reader = new Thread(() -> {
            try {
                readVideo();
                listener.onEnd("stream ended");
            } catch (IOException e) {
                listener.onEnd(e.getMessage());
            }
        }, "scrcpy-video");
        reader.setDaemon(true);
        reader.start();
        if (control != null) {
            Adb.Channel c = control;
            Thread drainControl = new Thread(() -> {
                try {
                    while (c.next(Long.MAX_VALUE) != null) {
                        // device messages (clipboard etc.) are not used
                    }
                } catch (IOException ignored) {
                    // channel closed
                }
            }, "scrcpy-control-drain");
            drainControl.setDaemon(true);
            drainControl.start();
        }
    }

    /** The first socket gets a dummy byte once the server really listens: retry until that byte arrives. */
    private Adb.Channel connectVideo(String socket, long deadline) throws IOException {
        while (true) {
            Adb.Channel c = null;
            try {
                c = adb.open(socket);
                c.readExact(1, 2000);
                return c;
            } catch (IOException e) {
                if (c != null) c.close();
                if (System.currentTimeMillis() > deadline) throw new IOException("scrcpy server did not start", e);
                sleepQuietly(150);
            }
        }
    }

    /** The server accepts a socket only once it listens, so an early open fails: retry until the deadline. */
    private Adb.Channel connect(String socket, long deadline) throws IOException {
        while (true) {
            try {
                return adb.open(socket);
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) throw new IOException("scrcpy server did not start", e);
                sleepQuietly(150);
            }
        }
    }

    private static void sleepQuietly(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private void readVideo() throws IOException {
        while (!closed) {
            ByteBuffer header = ByteBuffer.wrap(video.readExact(12, Long.MAX_VALUE));
            if ((header.get(0) & 0x80) != 0) { // session packet: device video size
                width = header.getInt(4);
                height = header.getInt(8);
                listener.onVideoSize(width, height);
                continue;
            }
            long flagsPts = header.getLong(0);
            int size = header.getInt(8);
            byte[] payload = video.readExact(size, Long.MAX_VALUE);
            if ((flagsPts & CONFIG_FLAG) != 0) {
                listener.onConfig(payload);
            } else {
                listener.onFrame(payload, flagsPts & PTS_MASK);
            }
        }
    }

    /** fx, fy = 0..1 of the picture. Dropped while the size is unknown or control is off. */
    public void touch(int action, float fx, float fy) {
        Adb.Channel c = control;
        if (c == null || width == 0) return;
        int x = Math.round(clamp(fx) * (width - 1));
        int y = Math.round(clamp(fy) * (height - 1));
        try {
            c.write(touchMessage(action, x, y, width, height));
        } catch (IOException ignored) {
            // link dropped; the reader reports the end
        }
    }

    static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** INJECT_TOUCH_EVENT, 32 bytes big-endian (scrcpy app/tests/test_control_msg_serialize.c). */
    static byte[] touchMessage(int action, int x, int y, int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.put((byte) 2).put((byte) action).putLong(POINTER_FINGER).putInt(x).putInt(y)
                .putShort((short) width).putShort((short) height)
                .putShort((short) (action == TOUCH_UP ? 0 : 0xFFFF)).putInt(0).putInt(0);
        return b.array();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Adb.Channel c : new Adb.Channel[]{control, video, shell}) {
            if (c != null) c.close();
        }
    }
}
