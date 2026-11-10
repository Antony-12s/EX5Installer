import com.wasak.ex5installer.Adb;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.zip.*;

/** Runs the generated install script in /bin/sh with fake `settings` and `pm`, checks the verifier is restored. */
public class ScriptTest {
    static int fails;

    public static void main(String[] a) throws Exception {
        // verifier was 1 -> set 0 during install, back to 1 after
        check("single, verifier 1", run(1, "1", true, false), "1", "pm install -r -g --user 11", "RC=0");
        // verifier unset -> deleted again
        check("single, verifier unset", run(1, "null", true, false), "null", "pm install", "RC=0");
        // owner keeps it 0 -> untouched
        check("single, verifier 0", run(1, "0", true, false), "0", "pm install", "RC=0");
        // install fails -> still restored, RC != 0
        check("single, pm fails", run(1, "1", true, true), "1", "pm install", "RC=1");
        // split session
        check("split", run(3, "1", true, false), "1", "pm install-commit 77", "RC=0");
        check("split, write fails", run(3, "1", true, true), "1", "pm install-abandon 77", "RC=1");
        // checkbox off -> setting never written
        String off = run(1, "1", false, false);
        check("verifier off", off, "1", "pm install", "RC=0");
        if (off.contains("settings put")) fail("verifier off still wrote the setting");

        bundleTest();
        keyTest();
        System.out.println(fails == 0 ? "ScriptTest: all passed" : "ScriptTest: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    /** Returns the call log plus final setting value on the last line. */
    static String run(int apks, String setting, boolean verifierOff, boolean pmFails) throws Exception {
        Path d = Files.createTempDirectory("ex5i");
        Path bin = Files.createDirectories(d.resolve("bin"));
        Path dir = Files.createDirectories(d.resolve("ex5i"));
        for (int i = 0; i < apks; i++) Files.write(dir.resolve(i + ".apk"), new byte[]{1});
        Path state = d.resolve("setting");
        Files.writeString(state, setting);
        Path calls = d.resolve("calls");
        stub(bin, "settings", "echo \"settings $*\" >> " + calls + "\n"
                + "case $1 in get) cat " + state + ";; put) echo -n $4 > " + state + ";; delete) echo -n null > " + state + ";; esac\n");
        stub(bin, "pm", "echo \"pm $*\" >> " + calls + "\n"
                + "case $1 in install-create) echo 'Success: created install session [77]';;\n"
                + " install-write) " + (pmFails ? "exit 1" : "true") + ";;\n"
                + " install) " + (pmFails ? "echo Failure; exit 1" : "echo Success") + ";;\n"
                + " install-commit) echo Success;; esac\n");
        String script = callScript(apks, verifierOff).replace("/data/local/tmp/ex5i", dir.toString());
        Path sh = d.resolve("run.sh");
        Files.writeString(sh, script);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", sh.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        String log = Files.exists(calls) ? Files.readString(calls) : "";
        if (Files.exists(dir)) fail("temp dir not removed");
        return log + out + "\nFINAL=" + Files.readString(state);
    }

    static String callScript(int n, boolean off) throws Exception {
        var m = Class.forName("com.wasak.ex5installer.Installer").getDeclaredMethod("script", int.class, String.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, n, "11", off);
    }

    static void stub(Path bin, String name, String body) throws IOException {
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body);
        f.toFile().setExecutable(true);
    }

    static void check(String name, String out, String finalValue, String mustContain, String rc) {
        boolean ok = out.endsWith("FINAL=" + finalValue) && out.contains(mustContain) && out.contains(rc) && out.contains("END");
        if (!ok) fail(name + ":\n" + out);
        else System.out.println("ok  " + name);
    }

    static void bundleTest() throws Exception {
        var m = Class.forName("com.wasak.ex5installer.Installer").getDeclaredMethod("bundleEntries", InputStream.class);
        m.setAccessible(true);
        ByteArrayOutputStream apk = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(apk)) { z.putNextEntry(new ZipEntry("AndroidManifest.xml")); z.write(1); }
        if (m.invoke(null, new ByteArrayInputStream(apk.toByteArray())) != null) fail("plain apk seen as bundle");
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bundle)) {
            for (String n : new String[]{"info.json", "base.apk", "split_config.arm64_v8a.apk", "icon.png"}) { z.putNextEntry(new ZipEntry(n)); z.write(1); }
        }
        Object r = m.invoke(null, new ByteArrayInputStream(bundle.toByteArray()));
        if (!List.of("base.apk", "split_config.arm64_v8a.apk").equals(r)) fail("bundle entries: " + r);
        else System.out.println("ok  bundle entries");
    }

    /** The adb public key blob must round-trip n and e (adbd parses it as mincrypt RSAPublicKey). */
    static void keyTest() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        var m = Adb.class.getDeclaredMethod("publicKeyPayload", RSAPublicKey.class);
        m.setAccessible(true);
        String s = new String((byte[]) m.invoke(null, k.getPublic()));
        byte[] blob = Base64.getDecoder().decode(s.substring(0, s.indexOf(' ')));
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int words = b.getInt();
        int n0inv = b.getInt();
        byte[] nLe = new byte[words * 4];
        b.get(nLe);
        byte[] be = new byte[nLe.length + 1];
        for (int i = 0; i < nLe.length; i++) be[be.length - 1 - i] = nLe[i];
        java.math.BigInteger n = new java.math.BigInteger(be);
        RSAPublicKey pub = (RSAPublicKey) k.getPublic();
        java.math.BigInteger r32 = java.math.BigInteger.ONE.shiftLeft(32);
        boolean ok = words == 64 && n.equals(pub.getModulus())
                && n.multiply(java.math.BigInteger.valueOf(n0inv & 0xffffffffL)).add(java.math.BigInteger.ONE).mod(r32).signum() == 0
                && s.endsWith("\0");
        b.position(8 + words * 8);
        ok &= b.getInt() == 65537;
        // rr = (2^(2*bits)) mod n, little-endian words, between n and e
        b.position(8 + words * 4);
        byte[] rrLe = new byte[words * 4];
        b.get(rrLe);
        byte[] rrBe = new byte[rrLe.length + 1];
        for (int i = 0; i < rrLe.length; i++) rrBe[rrBe.length - 1 - i] = rrLe[i];
        ok &= new java.math.BigInteger(rrBe).equals(java.math.BigInteger.ONE.shiftLeft(words * 64).mod(n));
        if (!ok) fail("adb key blob wrong"); else System.out.println("ok  adb key blob");

        // adbd checks RSA_verify(NID_sha1, token, 20, sig): PKCS#1 v1.5 over SHA-1 DigestInfo + the raw token.
        byte[] token = new byte[20];
        new java.util.Random(2).nextBytes(token);
        var sign = Adb.class.getDeclaredMethod("sign", KeyPair.class, byte[].class);
        sign.setAccessible(true);
        byte[] sig = (byte[]) sign.invoke(null, k, token);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, k.getPublic());
        byte[] plain = c.doFinal(sig);
        byte[] want = new byte[35];
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        System.arraycopy(prefix, 0, want, 0, 15);
        System.arraycopy(token, 0, want, 15, 20);
        if (sig.length != 256 || !java.util.Arrays.equals(plain, want)) fail("auth signature not PKCS1 SHA-1 DigestInfo");
        else System.out.println("ok  auth signature");
    }

    static void fail(String why) { fails++; System.out.println("FAIL " + why); }
}
