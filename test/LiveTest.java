import com.wasak.ex5installer.Adb;
import com.wasak.ex5installer.Installer;
import com.wasak.ex5installer.Scrcpy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.*;

/** Against a real device: LiveTest <host> <port> [apk-or-bundle]. Uses a key kept in ~/.android/ex5i-livekey (PKCS8/X509). */
public class LiveTest {
    public static void main(String[] a) throws Exception {
        KeyPair key = key(Paths.get(System.getProperty("user.home"), ".android", "ex5i-livekey"));
        try (Adb adb = Adb.connect(a[0], Integer.parseInt(a[1]), key, () -> System.out.println("tap Allow on the device"))) {
            System.out.println("banner: " + adb.banner);
            String echo = adb.exec("echo hello; id -u").trim();
            System.out.println("exec: " + echo.replace('\n', ' '));
            if (!echo.startsWith("hello")) throw new AssertionError("exec");
            byte[] data = new byte[300_000];
            new java.util.Random(1).nextBytes(data);
            adb.push(new ByteArrayInputStream(data), "/data/local/tmp/ex5i-push-test", null);
            String sum = adb.exec("sha256sum /data/local/tmp/ex5i-push-test; rm /data/local/tmp/ex5i-push-test").split(" ")[0];
            String want = hex(MessageDigest.getInstance("SHA-256").digest(data));
            System.out.println("push sha256 " + (sum.equals(want) ? "match" : "MISMATCH " + sum));
            if (!sum.equals(want)) throw new AssertionError("push");
            if (System.getenv("EX5_SCREEN") != null) screen(adb, Paths.get("assets/scrcpy-server.jar"));
            if (a.length > 2) {
                Path f = Paths.get(a[2]);
                boolean ok = Installer.install(adb, new Installer.Source() {
                    public String name() { return f.getFileName().toString(); }
                    public InputStream open() throws IOException { return Files.newInputStream(f); }
                }, true, System.out::println);
                if (!ok) throw new AssertionError("install");
            }
        }
        System.out.println("LiveTest: passed");
    }

    /** Real stream from the device: dumps H.264 to out/screen.h264 for an external decoder check. */
    static void screen(Adb adb, Path jar) throws Exception {
        ByteArrayOutputStream dump = new ByteArrayOutputStream();
        AtomicInteger frames = new AtomicInteger();
        int[] size = new int[2];
        byte[][] config = new byte[1][];
        CompletableFuture<String> ended = new CompletableFuture<>();
        Scrcpy s = Scrcpy.start(adb, Files.newInputStream(jar), 0, true, new Scrcpy.Listener() {
            public void onVideoSize(int w, int h) { size[0] = w; size[1] = h; System.out.println("size " + w + "x" + h); }
            public void onConfig(byte[] d) {
                config[0] = d;
                synchronized (dump) { dump.write(d, 0, d.length); }
                System.out.println("config " + d.length + " bytes, first bytes " + hex(java.util.Arrays.copyOf(d, 6)));
            }
            public void onFrame(byte[] d, long pts) {
                frames.incrementAndGet();
                synchronized (dump) { dump.write(d, 0, d.length); }
            }
            public void onEnd(String reason) { ended.complete(reason); }
        });
        Thread.sleep(6000);
        System.out.println("frames in 6s: " + frames.get() + ", video " + size[0] + "x" + size[1]);
        s.touch(Scrcpy.TOUCH_DOWN, 0.5f, 0.5f);
        Thread.sleep(80);
        s.touch(Scrcpy.TOUCH_UP, 0.5f, 0.5f);
        System.out.println("tap sent at centre");
        s.close();
        String why;
        try {
            why = ended.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            why = "(no end signal in 3s)";
        }
        System.out.println("stream end: " + why);
        Files.write(Paths.get("out/screen.h264"), dump.toByteArray());
        System.out.println("server processes left on device: " + adb.exec("ps -A 2>/dev/null | grep -c genymobile").trim());
        if (config[0] == null || (config[0][4] & 0x1f) != 7) throw new AssertionError("first config is not SPS (nal 7)");
        if (size[0] == 0 || frames.get() < 10) throw new AssertionError("too few frames: " + frames.get());
    }

    static KeyPair key(Path dir) throws Exception {
        Path pri = dir.resolve("adbkey"), pub = dir.resolve("adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (Files.exists(pri)) return new KeyPair(
                kf.generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(pub))),
                kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Files.readAllBytes(pri))));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.createDirectories(dir);
        Files.write(pri, k.getPrivate().getEncoded());
        Files.write(pub, k.getPublic().getEncoded());
        return k;
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
}