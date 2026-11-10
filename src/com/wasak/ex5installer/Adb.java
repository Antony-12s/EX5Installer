package com.wasak.ex5installer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Minimal ADB client over plain TCP (the `adb tcpip` / `<ip>:5566` transport, not Android 11 TLS pairing).
 * One stream at a time: exec (raw shell, no pty) and sync push. Pure Java so it also runs on the PC for tests.
 */
public final class Adb implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DigestInfo header for SHA-1: adbd verifies the token as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int maxData = 4096;
    private int nextId = 1;
    public String banner = "";

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects and authenticates. onKeyPrompt runs when the device must show "Allow debugging?" for our key. */
    public static Adb connect(String host, int port, KeyPair key, Runnable onKeyPrompt) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(10000);
        s.setTcpNoDelay(true);
        Adb adb = new Adb(s);
        try {
            adb.handshake(key, onKeyPrompt);
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
        return adb;
    }

    private void handshake(KeyPair key, Runnable onKeyPrompt) throws IOException {
        send(CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) {
                maxData = Math.min(m.arg1, 256 * 1024);
                banner = new String(m.data, StandardCharsets.UTF_8).trim();
                socket.setSoTimeout(30000);
                return;
            }
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected handshake reply " + Integer.toHexString(m.cmd));
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                signed = true;
            } else {
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload((RSAPublicKey) key.getPublic()));
                if (onKeyPrompt != null) onKeyPrompt.run();
                socket.setSoTimeout(60000); // the user has to tap Allow on the device
            }
        }
    }

    static byte[] sign(KeyPair key, byte[] token) throws IOException {
        try {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(key.getPrivate());
            sig.update(SHA1_PREFIX);
            sig.update(token);
            return sig.sign();
        } catch (Exception e) {
            throw new IOException("sign failed: " + e, e);
        }
    }

    /** adb's own public key format: base64 of the mincrypt RSAPublicKey struct, then " name\0". */
    static byte[] publicKeyPayload(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        int words = n.bitLength() / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n.mod(r32).modInverse(r32).negate().mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String text = Base64.getEncoder().encodeToString(b.array()) + " ex5installer@android\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** ro.product.model from the CNXN banner ("device::ro.product.name=..;ro.product.model=Geely EX5;.."). */
    public String model() {
        for (String part : banner.split(";")) {
            if (part.startsWith("ro.product.model=")) return part.substring(17);
        }
        return banner;
    }

    /** Runs a shell command (no pty) and returns everything it printed. */
    public String exec(String cmd) throws IOException {
        Stream st = open("exec:" + cmd);
        while (!st.closed) pump(st);
        return st.inbox.toString("UTF-8");
    }

    /** Pushes src to remotePath with the sync protocol; returns bytes sent. Does not close src. */
    public long push(InputStream src, String remotePath, java.util.function.LongConsumer progress) throws IOException {
        Stream st = open("sync:");
        byte[] path = (remotePath + ",33188").getBytes(StandardCharsets.UTF_8); // 0100644
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        syncHeader(pending, "SEND", path.length);
        pending.write(path);
        byte[] buf = new byte[64 * 1024]; // SYNC_DATA_MAX
        long total = 0;
        int n;
        while ((n = readFully(src, buf)) > 0) {
            syncHeader(pending, "DATA", n);
            pending.write(buf, 0, n);
            write(st, pending.toByteArray());
            pending.reset();
            total += n;
            if (progress != null) progress.accept(total);
        }
        syncHeader(pending, "DONE", (int) (System.currentTimeMillis() / 1000));
        write(st, pending.toByteArray());
        while (st.inbox.size() < 8 && !st.closed) pump(st);
        byte[] reply = st.inbox.toByteArray();
        String id = reply.length >= 4 ? new String(reply, 0, 4, StandardCharsets.US_ASCII) : "";
        if (!id.equals("OKAY")) {
            while (!st.closed && st.inbox.size() < 8 + le32(reply, 4)) pump(st);
            reply = st.inbox.toByteArray();
            String why = reply.length > 8 ? new String(reply, 8, reply.length - 8, StandardCharsets.UTF_8) : "connection closed";
            throw new IOException("push " + remotePath + " failed: " + why);
        }
        pending.reset();
        syncHeader(pending, "QUIT", 0);
        write(st, pending.toByteArray());
        closeStream(st);
        return total;
    }

    private static int readFully(InputStream src, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = src.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void syncHeader(ByteArrayOutputStream o, String id, int value) {
        o.write(id.charAt(0)); o.write(id.charAt(1)); o.write(id.charAt(2)); o.write(id.charAt(3));
        o.write(value); o.write(value >>> 8); o.write(value >>> 16); o.write(value >>> 24);
    }

    private static int le32(byte[] b, int off) {
        if (b.length < off + 4) return 0;
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    // ---- streams ----

    private static final class Stream {
        final int local;
        int remote;
        boolean closed, okay;
        final ByteArrayOutputStream inbox = new ByteArrayOutputStream();
        Stream(int local) { this.local = local; }
    }

    private Stream open(String dest) throws IOException {
        Stream st = new Stream(nextId++);
        send(OPEN, st.local, 0, (dest + "\0").getBytes(StandardCharsets.UTF_8));
        while (st.remote == 0) {
            if (st.closed) throw new IOException("device refused " + dest);
            pump(st);
        }
        return st;
    }

    private void write(Stream st, byte[] data) throws IOException {
        for (int off = 0; off < data.length; off += maxData) {
            int len = Math.min(maxData, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            st.okay = false;
            send(WRTE, st.local, st.remote, chunk);
            while (!st.okay) {
                if (st.closed) throw new IOException("stream closed by device");
                pump(st);
            }
        }
    }

    private void closeStream(Stream st) throws IOException {
        if (!st.closed) send(CLSE, st.local, st.remote, new byte[0]);
        st.closed = true;
    }

    /** Reads one packet and applies it to st; packets for older streams are acknowledged and dropped. */
    private void pump(Stream st) throws IOException {
        Msg m = read();
        boolean mine = m.arg1 == st.local;
        switch (m.cmd) {
            case OKAY:
                if (mine) { st.remote = m.arg0; st.okay = true; }
                break;
            case WRTE:
                send(OKAY, m.arg1, m.arg0, new byte[0]);
                if (mine) st.inbox.write(m.data);
                break;
            case CLSE:
                if (mine) st.closed = true;
                break;
            default:
                break;
        }
    }

    // ---- packets ----

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] head = new byte[24];
        try {
            in.readFully(head);
        } catch (EOFException e) {
            throw new IOException("device closed the connection");
        }
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
