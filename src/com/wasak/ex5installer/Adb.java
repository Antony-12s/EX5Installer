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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Minimal ADB client over plain TCP (`adb tcpip` / `<ip>:5566`, not Android 11 TLS pairing).
 * One TCP connection carries many channels (exec, sync, shell, localabstract:...). A reader thread
 * demuxes packets into per-channel queues, so a long-running channel never blocks the others.
 */
public final class Adb implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DigestInfo header for SHA-1: adbd verifies the token as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] EOF = new byte[0];
    private static final int MAX_PAYLOAD = 1024 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean dead;
    private int maxData = 4096;
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
        s.setSoTimeout(0);
        Thread reader = new Thread(adb::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();
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
        Channel c = open("exec:" + cmd);
        try {
            return new String(c.readAll(), StandardCharsets.UTF_8);
        } finally {
            c.close();
        }
    }

    /** Pushes src to remotePath with the sync protocol; returns bytes sent. Does not close src. */
    public long push(InputStream src, String remotePath, LongConsumer progress) throws IOException {
        Channel c = open("sync:");
        try {
            byte[] path = (remotePath + ",33188").getBytes(StandardCharsets.UTF_8); // 0100644
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            syncHeader(head, "SEND", path.length);
            head.write(path);
            c.write(head.toByteArray());
            byte[] buf = new byte[64 * 1024]; // SYNC_DATA_MAX
            long total = 0;
            int n;
            while ((n = readFully(src, buf)) > 0) {
                ByteArrayOutputStream pkt = new ByteArrayOutputStream(n + 8);
                syncHeader(pkt, "DATA", n);
                pkt.write(buf, 0, n);
                c.write(pkt.toByteArray());
                total += n;
                if (progress != null) progress.accept(total);
            }
            ByteArrayOutputStream done = new ByteArrayOutputStream();
            syncHeader(done, "DONE", (int) (System.currentTimeMillis() / 1000));
            c.write(done.toByteArray());
            byte[] reply = c.readExact(8, 120_000);
            if (!"OKAY".equals(new String(reply, 0, 4, StandardCharsets.US_ASCII))) {
                byte[] msg = c.readExact(le32(reply, 4), 120_000);
                throw new IOException("push " + remotePath + " failed: " + new String(msg, StandardCharsets.UTF_8));
            }
            return total;
        } finally {
            c.close();
        }
    }

    /** Opens a channel to a service such as "shell:...", "sync:" or "localabstract:name". */
    public Channel open(String destination) throws IOException {
        if (dead) throw new IOException("adb connection closed");
        Channel c = new Channel(nextId.getAndIncrement());
        channels.put(c.local, c);
        send(OPEN, c.local, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        try {
            c.awaitOkay();
        } catch (IOException e) {
            channels.remove(c.local);
            throw new IOException("open failed: " + destination + " (" + e.getMessage() + ")", e);
        }
        return c;
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

    /** One logical stream on the shared connection. */
    public final class Channel implements Closeable {
        final int local;
        private volatile int remote;
        private volatile boolean closed;
        private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        private final Semaphore okay = new Semaphore(0);
        private byte[] pending = EMPTY;
        private int pendingAt;

        Channel(int local) {
            this.local = local;
        }

        void deliver(byte[] data) {
            if (data.length > 0 && !closed) inbox.add(data);
        }

        void acknowledge(int remoteId) {
            remote = remoteId;
            okay.release();
        }

        void remoteClosed() {
            closed = true;
            inbox.add(EOF);
            okay.release();
        }

        void awaitOkay() throws IOException {
            try {
                if (!okay.tryAcquire(30, TimeUnit.SECONDS)) throw new IOException("device did not answer");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (closed) throw new IOException("stream closed by device");
        }

        /** Sends data, split to the device's max packet size; each packet waits for the device's OKAY. */
        public synchronized void write(byte[] data) throws IOException {
            for (int off = 0; off < data.length; off += maxData) {
                int len = Math.min(maxData, data.length - off);
                byte[] chunk = new byte[len];
                System.arraycopy(data, off, chunk, 0, len);
                if (closed) throw new IOException("stream closed");
                send(WRTE, local, remote, chunk);
                awaitOkay();
            }
        }

        /** Next chunk, or null once the device closed the stream. */
        public byte[] next(long timeoutMs) throws IOException {
            try {
                byte[] d = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (d == null) throw new IOException("timeout");
                if (d == EOF) {
                    inbox.add(EOF); // keep it for the next reader
                    return null;
                }
                return d;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }

        public byte[] readExact(int n, long timeoutMs) throws IOException {
            byte[] result = new byte[n];
            int got = 0;
            while (got < n) {
                if (pendingAt == pending.length) {
                    byte[] d = next(timeoutMs);
                    if (d == null) throw new IOException("stream closed");
                    pending = d;
                    pendingAt = 0;
                }
                int take = Math.min(n - got, pending.length - pendingAt);
                System.arraycopy(pending, pendingAt, result, got, take);
                pendingAt += take;
                got += take;
            }
            return result;
        }

        public byte[] readAll() throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(pending, pendingAt, pending.length - pendingAt);
            pendingAt = pending.length;
            byte[] d;
            while ((d = next(120_000)) != null) o.write(d);
            return o.toByteArray();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    if (!dead) send(CLSE, local, remote, EMPTY);
                } catch (IOException ignored) {
                    // connection already gone
                }
            }
            channels.remove(local);
            inbox.add(EOF);
            okay.release();
        }
    }

    /** Demuxes packets into channels until the socket dies. */
    private void readLoop() {
        try {
            while (true) {
                Msg m = read();
                Channel c = channels.get(m.arg1);
                switch (m.cmd) {
                    case OKAY:
                        if (c != null) c.acknowledge(m.arg0);
                        break;
                    case WRTE:
                        if (c != null) {
                            c.deliver(m.data);
                            send(OKAY, c.local, m.arg0, EMPTY);
                        } else {
                            send(CLSE, m.arg1, m.arg0, EMPTY);
                        }
                        break;
                    case CLSE:
                        if (c != null) {
                            channels.remove(m.arg1);
                            c.remoteClosed();
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            // socket closed or broken: every channel ends below
        }
        dead = true;
        for (Channel c : channels.values()) c.remoteClosed();
        channels.clear();
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
        synchronized (writeLock) {
            out.write(h.array());
            out.write(data);
            out.flush();
        }
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
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("bad packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        dead = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
