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

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** Our adb RSA key, made once and kept in app storage (the car remembers it after the first Allow). */
public final class Keys {
    private Keys() { }

    public static KeyPair get(Context context) throws Exception {
        File pri = new File(context.getFilesDir(), "adbkey"), pub = new File(context.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(pri.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.write(pri.toPath(), k.getPrivate().getEncoded());
        Files.write(pub.toPath(), k.getPublic().getEncoded());
        return k;
    }
}
