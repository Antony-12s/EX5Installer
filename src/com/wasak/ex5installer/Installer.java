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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs one .apk / .apkm / .apks / .xapk file on the device behind an Adb connection. */
public final class Installer {
    static final String DIR = "/data/local/tmp/ex5i";
    static final String LOG = "/data/local/tmp/ex5i.log";

    public interface Source {
        String name();
        InputStream open() throws IOException;
    }

    public interface Log {
        void line(String text);
    }

    private Installer() { }

    /**
     * The script that runs ON the device, detached, so a dropped Wi-Fi link cannot stop it half-way.
     * verifierOff: EX5 firmware 1.3 (com.ecarx.xsfinstallverifier) rejects every shell install while
     * verifier_verify_adb_installs is on. Save it, set 0, and put the old value back in the EXIT trap
     * (null = unset: delete again; a saved 0 means the owner keeps it off, so nothing is written).
     */
    static String script(int apkCount, String user, boolean verifierOff) {
        StringBuilder s = new StringBuilder();
        String restore = "";
        if (verifierOff) {
            s.append("V=$(settings get global verifier_verify_adb_installs)\n");
            s.append("settings put global verifier_verify_adb_installs 0\n");
            restore = "if [ \"$V\" = null ]; then settings delete global verifier_verify_adb_installs; "
                    + "elif [ \"$V\" != 0 ]; then settings put global verifier_verify_adb_installs \"$V\"; fi; ";
        }
        s.append("trap '").append(restore).append("rm -rf ").append(DIR).append("; echo END' EXIT\n");
        String flags = "-r -g --user " + user;
        if (apkCount == 1) {
            s.append("pm install ").append(flags).append(' ').append(DIR).append("/0.apk; R=$?\n");
        } else {
            // A split app as ONE session (what `adb install-multiple` does); any failure abandons it.
            s.append("S=$(pm install-create ").append(flags).append(" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n");
            s.append("if [ -z \"$S\" ]; then echo 'pm install-create failed'; R=1; else\n");
            s.append("  R=0\n");
            s.append("  for f in ").append(DIR).append("/*.apk; do n=${f##*/}; ");
            s.append("pm install-write $S split$n $f > /dev/null || { echo \"pm install-write failed: $n\"; R=1; break; }; done\n");
            s.append("  if [ $R = 0 ]; then pm install-commit $S; R=$?; else pm install-abandon $S; fi\n");
            s.append("fi\n");
        }
        s.append("echo \"RC=$R\"\nexit $R\n");
        return s.toString();
    }

    /** Entry names worth installing: every *.apk inside a bundle, or null when the file itself is an APK. */
    static List<String> bundleEntries(InputStream raw) throws IOException {
        List<String> apks = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("AndroidManifest.xml")) return null;
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".apk")) apks.add(e.getName());
            }
        }
        return apks;
    }

    /** Returns true when the device printed Success. */
    public static boolean install(Adb adb, Source src, boolean verifierOff, Log log) throws IOException {
        log.line("ไฟล์: " + src.name());
        String user = adb.exec("am get-current-user").trim();
        if (!user.matches("\\d+")) user = "0";
        log.line("ลงให้ user " + user);

        List<String> entries;
        try (InputStream in = src.open()) {
            entries = bundleEntries(in);
        }
        adb.exec("rm -rf " + DIR + "; mkdir -p " + DIR);
        int count;
        if (entries == null) {
            try (InputStream in = src.open()) {
                long n = adb.push(in, DIR + "/0.apk", null);
                log.line("ส่ง APK แล้ว " + n / 1024 + " KB");
            }
            count = 1;
        } else {
            if (entries.isEmpty()) throw new IOException("ไม่เจอ APK ในไฟล์นี้ (APKM แบบเข้ารหัสรุ่นเก่าใช้ไม่ได้)");
            log.line("ไฟล์รวม มี " + entries.size() + " ชิ้น");
            count = 0;
            try (ZipInputStream zip = new ZipInputStream(src.open())) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (!entries.contains(e.getName())) continue;
                    long n = adb.push(zip, DIR + "/" + count + ".apk", null);
                    log.line("  ส่ง " + e.getName() + " " + n / 1024 + " KB");
                    count++;
                }
            }
        }

        String before = adb.exec("settings get global verifier_verify_adb_installs").trim();
        if (verifierOff) log.line("ตัวตรวจติดตั้งตอนนี้ = " + before + " (ปิดชั่วคราว แล้วคืนค่าเอง)");
        adb.push(new ByteArrayInputStream(script(count, user, verifierOff).getBytes(StandardCharsets.UTF_8)), DIR + "/run.sh", null);
        // toybox `setsid -d` returns at once and leaves the script running on the device.
        adb.exec("rm -f " + LOG + "; (setsid -d sh " + DIR + "/run.sh || sh " + DIR + "/run.sh) < /dev/null > " + LOG + " 2>&1; echo started");
        log.line("กำลังติดตั้งบนรถ...");

        String out = "";
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
        while (!out.contains("END")) {
            if (System.currentTimeMillis() > deadline) throw new IOException("รอเกิน 10 นาที ดูผลเองได้ที่ " + LOG);
            sleep(1000);
            out = adb.exec("cat " + LOG + " 2>/dev/null");
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.equals("END") && !t.startsWith("RC=")) log.line("  " + t);
        }
        String after = adb.exec("settings get global verifier_verify_adb_installs").trim();
        if (verifierOff) log.line("ตัวตรวจติดตั้งหลังลง = " + after + (after.equals(before) ? " (คืนค่าแล้ว)" : " (ไม่ตรงค่าเดิม " + before + "!)"));
        boolean ok = out.contains("RC=0") && out.contains("Success");
        log.line(ok ? "ลงสำเร็จ" : "ลงไม่สำเร็จ");
        return ok;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
