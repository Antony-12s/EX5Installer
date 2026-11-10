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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Shared dark theme for the EX5 Installer screens: one palette, one spacing scale, and small
 * builders so both activities look the same. In-car context, so dark-first, big touch targets
 * (min 48dp) and plain flat surfaces (no heavy blur that a weak head unit cannot draw).
 *
 * This file is the UI contract. Activities CALL these helpers; they must not redefine the colors.
 */
public final class Ui {
    private Ui() { }

    // Palette (ARGB). Dark, calm, one blue accent.
    public static final int BG = 0xFF0E1116;          // screen background
    public static final int CARD = 0xFF181D25;        // raised surface
    public static final int CARD_HI = 0xFF212834;     // input / chip surface
    public static final int BORDER = 0xFF2C3540;      // hairline
    public static final int TEXT = 0xFFE8EDF2;        // primary text
    public static final int TEXT_DIM = 0xFF93A1B0;    // secondary text / hints
    public static final int ACCENT = 0xFF3B9DFF;      // primary action / focus
    public static final int ACCENT_DK = 0xFF1C2C40;   // accent-tinted fill
    public static final int SUCCESS = 0xFF38D39F;
    public static final int DANGER = 0xFFF2616B;
    public static final int WARN = 0xFFF2B93B;
    public static final int ON_ACCENT = 0xFF081019;   // text on an accent button

    // Spacing scale (dp) and corner radius (dp).
    public static final int S1 = 8, S2 = 12, S3 = 16, S4 = 20, S5 = 24;
    public static final int RADIUS = 16, RADIUS_SM = 12, RADIUS_PILL = 999;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Rounded filled rectangle, optional hairline border. */
    public static GradientDrawable roundRect(Context c, int fill, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    /** A content card: CARD fill, hairline border, inner padding. Add your views to it. */
    public static android.widget.LinearLayout card(Context c) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(c);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setBackground(roundRect(c, CARD, RADIUS, BORDER));
        int p = dp(c, S3);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A screen title (big) + optional subtitle below. Returns the container. */
    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView subtitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(13);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }

    /** A small uppercase-ish section label above a group. */
    public static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Dark input field with rounded border; the border turns accent while focused. */
    public static void styleField(EditText e) {
        Context c = e.getContext();
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_DIM);
        e.setTextSize(16);
        e.setBackground(roundRect(c, CARD_HI, RADIUS_SM, BORDER));
        int px = dp(c, S3), py = dp(c, S2);
        e.setPadding(px, py, px, py);
        e.setMinHeight(dp(c, 52));
        e.setOnFocusChangeListener((v, has) ->
                v.setBackground(roundRect(c, CARD_HI, RADIUS_SM, has ? ACCENT : BORDER)));
    }

    /** Filled accent button, ~52dp tall, with a ripple. Use for the main action on a screen. */
    public static void primary(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(ON_ACCENT);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 52));
        b.setBackground(ripple(c, roundRect(c, ACCENT, RADIUS_SM, 0), 0x33FFFFFF));
        b.setStateListAnimator(null);
    }

    /** Outlined/secondary button on the dark surface. */
    public static void secondary(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 52));
        b.setBackground(ripple(c, roundRect(c, CARD_HI, RADIUS_SM, BORDER), 0x22FFFFFF));
        b.setStateListAnimator(null);
    }

    /** Small translucent pill button for overlays (e.g. a close button over video). */
    public static void overlayPill(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 44));
        b.setBackground(ripple(c, roundRect(c, 0xCC181D25, RADIUS_PILL, 0x55FFFFFF), 0x33FFFFFF));
        int px = dp(c, S4);
        b.setPadding(px, 0, px, 0);
        b.setStateListAnimator(null);
    }

    /** Tint a CheckBox/Switch and set its text colors. */
    public static void styleToggle(CompoundButton t) {
        t.setTextColor(TEXT);
        t.setTextSize(14);
        t.setButtonTintList(ColorStateList.valueOf(ACCENT));
    }

    private static RippleDrawable ripple(Context c, GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /** LinearLayout vertical margin helper: sets bottom margin on a child's layout params. */
    public static void marginBottom(View v, int dp) {
        if (v.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams()).bottomMargin = dp(v.getContext(), dp);
        }
    }
}
