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
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private EditText host, port;
    private CheckBox verifier;
    private TextView picked, log, connStatus;
    private View dot;
    private Button install, connect, screen;
    private LinearLayout filesBox;
    private final List<Uri> files = new ArrayList<>();
    private SharedPreferences prefs;

    // --- Connection state (single source of truth for render()) ---
    private volatile Adb car;          // the held, reusable connection; null when disconnected
    private boolean connecting;        // a connect attempt is in flight
    private boolean busy;              // an install run is in flight
    private String connMsg;            // transient connection status / error line; null = use default
    private String carModel, carHost, carPort; // snapshot of the connected device for the status line

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("main", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, Ui.S4);
        root.setPadding(pad, pad, pad, pad);

        // --- Header ---
        TextView title = Ui.title(this, "EX5 Installer");
        addRow(root, title, Ui.S1);
        TextView hint = Ui.subtitle(this, "ลงแอปเข้ารถผ่าน Wireless ADB — มือถือกับรถต้องอยู่ Wi-Fi เดียวกัน");
        addRow(root, hint, Ui.S4);

        // --- Card: การเชื่อมต่อ ---
        LinearLayout connCard = Ui.card(this);
        addInner(connCard, Ui.label(this, "การเชื่อมต่อ"), Ui.S2);
        host = field("IP รถ เช่น 192.168.1.158", prefs.getString("host", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addInner(connCard, host, Ui.S2);
        port = field("พอร์ต", prefs.getString("port", "5566"), InputType.TYPE_CLASS_NUMBER);
        addInner(connCard, port, Ui.S2);
        addInner(connCard, Ui.subtitle(this, "รถต้องเปิด ADB ผ่าน Wi-Fi (adb tcpip) ไว้ พอร์ตปกติของ EX5 คือ 5566 ไม่รองรับรหัสจับคู่ของ Wireless debugging"), Ui.S2);

        // Connection state indicator: a small coloured dot + status text.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Ui.TEXT_DIM);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.rightMargin = Ui.dp(this, Ui.S2);
        dot.setLayoutParams(dlp);
        statusRow.addView(dot);
        connStatus = new TextView(this);
        connStatus.setTextSize(14);
        connStatus.setTextColor(Ui.TEXT_DIM);
        connStatus.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(connStatus);
        addInner(connCard, statusRow, Ui.S2);

        connect = new Button(this);
        connect.setOnClickListener(v -> onConnectClick());
        addInner(connCard, connect, 0);
        addRow(root, connCard, Ui.S3);

        // --- Card: ลงแอป ---
        LinearLayout installCard = Ui.card(this);
        addInner(installCard, Ui.label(this, "ลงแอป"), Ui.S2);

        Button pick = new Button(this);
        pick.setText("เลือกไฟล์ APK / APKM / APKS / XAPK");
        Ui.secondary(pick);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK);
        });
        addInner(installCard, pick, Ui.S2);

        filesBox = new LinearLayout(this);
        filesBox.setOrientation(LinearLayout.VERTICAL);
        filesBox.setBackground(Ui.roundRect(this, Ui.CARD_HI, Ui.RADIUS_SM, Ui.BORDER));
        int fp = Ui.dp(this, Ui.S2);
        filesBox.setPadding(fp, fp, fp, fp);
        filesBox.setMinimumHeight(Ui.dp(this, 56));
        picked = new TextView(this);
        picked.setText("ยังไม่ได้เลือกไฟล์");
        picked.setTextColor(Ui.TEXT_DIM);
        picked.setTextSize(14);
        filesBox.addView(picked);
        addInner(installCard, filesBox, Ui.S2);

        verifier = new CheckBox(this);
        verifier.setText("ปิดตัวตรวจติดตั้งของ EX5 1.3 ชั่วคราว (คืนค่าเองหลังลงเสร็จ)");
        Ui.styleToggle(verifier);
        verifier.setChecked(prefs.getBoolean("verifier", true));
        addInner(installCard, verifier, Ui.S2);

        install = new Button(this);
        install.setText("ลงแอปในรถ");
        Ui.primary(install);
        install.setOnClickListener(v -> start());
        addInner(installCard, install, 0);
        addRow(root, installCard, Ui.S3);

        // --- Card: ดูจอรถ ---
        LinearLayout screenCard = Ui.card(this);
        addInner(screenCard, Ui.label(this, "ดูจอรถ"), Ui.S2);
        screen = new Button(this);
        screen.setText("ดูจอหลักของรถ (ควบคุมได้)");
        Ui.secondary(screen);
        screen.setOnClickListener(v -> {
            Intent i = new Intent(this, ScreenActivity.class)
                    .putExtra(ScreenActivity.EXTRA_HOST, host.getText().toString().trim())
                    .putExtra(ScreenActivity.EXTRA_PORT, Integer.parseInt(port.getText().toString().trim()));
            startActivity(i);
        });
        addInner(screenCard, screen, 0);
        addRow(root, screenCard, Ui.S3);

        // --- Card: สถานะ ---
        LinearLayout statusCard = Ui.card(this);
        addInner(statusCard, Ui.label(this, "สถานะ"), Ui.S2);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setTextColor(Ui.TEXT);
        log.setTextSize(13);
        log.setBackground(Ui.roundRect(this, Ui.CARD_HI, Ui.RADIUS_SM, Ui.BORDER));
        int lp = Ui.dp(this, Ui.S3);
        log.setPadding(lp, lp, lp, lp);
        log.setMinHeight(Ui.dp(this, 120));
        addInner(statusCard, log, 0);
        addRow(root, statusCard, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(root);
        setContentView(scroll);
        render();
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
        // Any app can send to this exported activity: keep only real Uris, a wrong extra type must not crash.
        Object one = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
        if (Intent.ACTION_SEND.equals(intent.getAction()) && one instanceof Uri) uris.add((Uri) one);
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()) && one instanceof java.util.List) {
            for (Object item : (java.util.List<?>) one) {
                if (item instanceof Uri) uris.add((Uri) item);
            }
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
        // Keep picked (the empty-state label) at index 0; drop any previous chips.
        if (filesBox.getChildCount() > 1) filesBox.removeViews(1, filesBox.getChildCount() - 1);
        if (files.isEmpty()) {
            picked.setVisibility(View.VISIBLE);
            picked.setText("ยังไม่ได้เลือกไฟล์");
            render();
            return;
        }
        picked.setVisibility(View.GONE);
        for (int i = 0; i < files.size(); i++) {
            TextView chip = new TextView(this);
            chip.setText(name(files.get(i)));
            chip.setTextColor(Ui.TEXT);
            chip.setTextSize(14);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.setBackground(Ui.roundRect(this, Ui.CARD, Ui.RADIUS_SM, Ui.BORDER));
            int cp = Ui.dp(this, Ui.S2);
            chip.setPadding(cp, cp, cp, cp);
            chip.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            filesBox.addView(chip);
            if (i < files.size() - 1) Ui.marginBottom(chip, Ui.S1);
        }
        render();
    }

    /** Single state-update method: drives every enabled/alpha/text from the current state. UI thread only. */
    private void render() {
        boolean connected = car != null;

        // Host/port are editable only while fully disconnected and idle.
        boolean editable = !connected && !connecting && !busy;
        host.setEnabled(editable);
        port.setEnabled(editable);

        // Connect / Disconnect button.
        if (connecting) {
            Ui.primary(connect);
            connect.setText("กำลังเชื่อมต่อ...");
            connect.setEnabled(false);
        } else if (connected) {
            Ui.secondary(connect);
            connect.setText("ตัดการเชื่อมต่อ");
            connect.setEnabled(!busy);
        } else {
            Ui.primary(connect);
            connect.setText("เชื่อมต่อ");
            connect.setEnabled(true);
        }

        // Connection status dot + line.
        GradientDrawable dotBg = (GradientDrawable) dot.getBackground();
        if (connecting) {
            dotBg.setColor(Ui.WARN);
            connStatus.setTextColor(Ui.TEXT_DIM);
            connStatus.setText(connMsg != null ? connMsg : "กำลังเชื่อมต่อ...");
        } else if (connected) {
            dotBg.setColor(Ui.SUCCESS);
            connStatus.setTextColor(Ui.TEXT);
            connStatus.setText("เชื่อมต่อแล้ว: " + carModel + " (" + carHost + ":" + carPort + ")");
        } else {
            dotBg.setColor(connMsg != null ? Ui.DANGER : Ui.TEXT_DIM);
            connStatus.setTextColor(connMsg != null ? Ui.DANGER : Ui.TEXT_DIM);
            connStatus.setText(connMsg != null ? connMsg : "ยังไม่ได้เชื่อมต่อ");
        }

        // Gated actions: enabled and full-opacity only when connected and idle.
        boolean actionable = connected && !busy && !connecting;
        install.setEnabled(actionable);
        install.setAlpha(connected ? 1f : 0.4f);
        screen.setEnabled(actionable);
        screen.setAlpha(connected ? 1f : 0.4f);
    }

    private void onConnectClick() {
        if (car != null) {
            disconnect();
            return;
        }
        final String h = host.getText().toString().trim();
        final String p = port.getText().toString().trim();
        if (h.isEmpty()) {
            connMsg = "ใส่ IP รถก่อน";
            render();
            return;
        }
        if (!p.matches("\\d+")) {
            connMsg = "พอร์ตต้องเป็นตัวเลข";
            render();
            return;
        }
        prefs.edit().putString("host", h).putString("port", p).apply();
        connecting = true;
        connMsg = null;
        render();
        final int portNum = Integer.parseInt(p);
        new Thread(() -> {
            try {
                Adb adb = Adb.connect(h, portNum, Keys.get(this),
                        () -> runOnUiThread(() -> {
                            connMsg = "รอกด Allow (อนุญาตการดีบัก) บนจอรถ...";
                            render();
                        }));
                final String model = adb.model();
                runOnUiThread(() -> {
                    car = adb;
                    carModel = model;
                    carHost = h;
                    carPort = p;
                    connecting = false;
                    connMsg = null;
                    render();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    connecting = false;
                    connMsg = "เชื่อมต่อไม่ได้: " + msg;
                    render();
                });
            }
        }).start();
    }

    private void disconnect() {
        final Adb old = car;
        car = null;
        carModel = carHost = carPort = null;
        connMsg = null;
        render();
        if (old != null) new Thread(old::close).start();
    }

    private void start() {
        if (car == null) return; // gated by render(), guard anyway
        if (files.isEmpty()) {
            log.setText("เลือกไฟล์ก่อน");
            return;
        }
        boolean verifierOff = verifier.isChecked();
        prefs.edit().putBoolean("verifier", verifierOff).apply();
        busy = true;
        render();
        log.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        List<Uri> todo = new ArrayList<>(files);
        final Adb adb = car;
        new Thread(() -> {
            int ok = 0;
            // One bad file (no read access, broken zip, install refused) must not stop the others
            // or drop the connection: only a failed liveness probe means the car link is gone.
            for (Uri u : todo) {
                Installer.Source src = new Installer.Source() {
                    public String name() { return MainActivity.this.name(u); }
                    public InputStream open() throws IOException {
                        InputStream in = getContentResolver().openInputStream(u);
                        if (in == null) throw new IOException("เปิดไฟล์ไม่ได้");
                        return in;
                    }
                };
                try {
                    if (Installer.install(adb, src, verifierOff, this::say)) ok++;
                } catch (Exception e) {
                    say("ผิดพลาด: " + e.getMessage());
                }
            }
            boolean linkDied;
            try {
                linkDied = !adb.exec("echo ok").trim().equals("ok");
            } catch (Exception e) {
                linkDied = true;
            }
            int done = ok;
            boolean died = linkDied;
            runOnUiThread(() -> {
                say("จบ: สำเร็จ " + done + "/" + todo.size());
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (died) {
                    say("การเชื่อมต่อหลุด กดเชื่อมต่อใหม่");
                    car = null;
                    carModel = carHost = carPort = null;
                    connMsg = null;
                    new Thread(adb::close).start();
                }
                render();
            });
        }).start();
    }

    private void say(String line) {
        runOnUiThread(() -> log.append(line + "\n"));
    }

    private String name(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) { }
        String last = u.getLastPathSegment();
        return last != null ? last : u.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        final Adb old = car;
        car = null;
        if (old != null) new Thread(old::close).start();
    }

    /** A styled dark input field. */
    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        Ui.styleField(e);
        return e;
    }

    /** Add a full-width child to the root column with a bottom margin. */
    private void addRow(LinearLayout parent, View v, int marginDp) {
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(v);
        if (marginDp > 0) Ui.marginBottom(v, marginDp);
    }

    /** Add a full-width child inside a card with a bottom margin. */
    private void addInner(LinearLayout card, View v, int marginDp) {
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(v);
        if (marginDp > 0) Ui.marginBottom(v, marginDp);
    }
}
