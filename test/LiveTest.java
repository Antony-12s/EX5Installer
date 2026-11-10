import com.wasak.ex5installer.Adb;
import com.wasak.ex5installer.Installer;
import java.io.*;
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
