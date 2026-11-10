package com.wasak.ex5installer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private EditText host, port;
    private CheckBox verifier;
    private TextView picked, log;
    private Button install;
    private final List<Uri> files = new ArrayList<>();
    private SharedPreferences prefs;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("main", MODE_PRIVATE);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("EX5 Installer");
        title.setTextSize(24);
        root.addView(title);
        TextView hint = new TextView(this);
        hint.setText("ลงแอปเข้ารถผ่าน Wireless ADB (มือถือกับรถต้องอยู่ Wi-Fi เดียวกัน)");
        root.addView(hint);

        host = field("IP รถ เช่น 192.168.1.158", prefs.getString("host", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field("พอร์ต", prefs.getString("port", "5566"), InputType.TYPE_CLASS_NUMBER);
        root.addView(host);
        root.addView(port);

        verifier = new CheckBox(this);
        verifier.setText("ปิดตัวตรวจติดตั้งของ EX5 1.3 ชั่วคราว (คืนค่าเองหลังลงเสร็จ)");
        verifier.setChecked(prefs.getBoolean("verifier", true));
        root.addView(verifier);

        Button pick = new Button(this);
        pick.setText("เลือกไฟล์ APK / APKM / APKS / XAPK");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK);
        });
        root.addView(pick);
        picked = new TextView(this);
        root.addView(picked);

        install = new Button(this);
        install.setText("ลงแอปในรถ");
        install.setOnClickListener(v -> start());
        root.addView(install);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        take(intent);
    }

    /** Files shared or opened with this app (Share / Open with from a file manager). */
    private void take(Intent intent) {
        if (intent == null || busy) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) uris.add(intent.getData());
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) setFiles(uris);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != PICK || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        setFiles(uris);
    }

    private void setFiles(List<Uri> uris) {
        files.clear();
        files.addAll(uris);
        StringBuilder s = new StringBuilder();
        for (Uri u : files) s.append("• ").append(name(u)).append('\n');
        picked.setText(s.toString().trim());
    }

    private void start() {
        String h = host.getText().toString().trim();
        String p = port.getText().toString().trim();
        if (h.isEmpty() || files.isEmpty()) {
            log.setText("ใส่ IP รถ แล้วเลือกไฟล์ก่อน");
            return;
        }
        boolean verifierOff = verifier.isChecked();
        prefs.edit().putString("host", h).putString("port", p).putBoolean("verifier", verifierOff).apply();
        busy = true;
        install.setEnabled(false);
        log.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        List<Uri> todo = new ArrayList<>(files);
        new Thread(() -> {
            int ok = 0;
            try (Adb adb = Adb.connect(h, Integer.parseInt(p), key(),
                    () -> say("รอกด Allow (อนุญาตการดีบัก) บนจอรถ..."))) {
                say("เชื่อมต่อแล้ว: " + adb.model());
                for (Uri u : todo) {
                    Installer.Source src = new Installer.Source() {
                        public String name() { return MainActivity.this.name(u); }
                        public InputStream open() throws IOException {
                            InputStream in = getContentResolver().openInputStream(u);
                            if (in == null) throw new IOException("เปิดไฟล์ไม่ได้");
                            return in;
                        }
                    };
                    if (Installer.install(adb, src, verifierOff, this::say)) ok++;
                }
            } catch (Exception e) {
                say("ผิดพลาด: " + e.getMessage());
            }
            int done = ok;
            runOnUiThread(() -> {
                say("จบ: สำเร็จ " + done + "/" + todo.size());
                busy = false;
                install.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }).start();
    }

    private void say(String line) {
        runOnUiThread(() -> log.append(line + "\n"));
    }

    /** Our adb RSA key, made once and kept in app storage (the car remembers it after the first Allow). */
    private KeyPair key() throws Exception {
        File pri = new File(getFilesDir(), "adbkey"), pub = new File(getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(pri.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(pri)) { o.write(k.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(k.getPublic().getEncoded()); }
        return k;
    }

    private String name(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) { }
        String last = u.getLastPathSegment();
        return last != null ? last : u.toString();
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
