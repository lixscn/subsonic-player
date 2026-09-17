package com.lixscn.subsonicplayer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务器密码的本地加密存储。
 *
 * 首选 AndroidKeyStore（密钥不出安全硬件/TEE，应用无法导出）；
 * 若设备不支持（极少数定制 ROM / 无 TEE 环境），退回「prefs 里的随机 AES 密钥」——
 * 安全性弱，但保证功能可用，并在日志中标注。**任何情况下都不明文落盘。**
 */
public final class Secret {

    private static final String TAG = "Secret";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "subsonic-player-master";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final String PREF_FALLBACK_KEY = "fallback_key_b64";

    private static boolean sKeyStoreOk = true;

    private Secret() {
    }

    /** 加密明文，返回可直接存 prefs 的 base64（iv||cipher）。失败返回空串。 */
    public static String protect(Context ctx, String plain) {
        if (plain == null || plain.length() == 0) return "";
        try {
            SecretKey key = key(ctx);
            if (key == null) return "";
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] data = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(data, 0, out, iv.length, data.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "加密失败", e);
            return "";
        }
    }

    /** 解密 protect() 的结果。任何失败返回空串（绝不抛异常）。 */
    public static String unprotect(Context ctx, String encB64) {
        if (encB64 == null || encB64.length() == 0) return "";
        try {
            byte[] all = Base64.decode(encB64, Base64.NO_WRAP);
            if (all.length < 13) return "";
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            byte[] data = new byte[all.length - 12];
            System.arraycopy(all, 12, data, 0, data.length);
            SecretKey key = key(ctx);
            if (key == null) return "";
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(data), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "解密失败（可能是换机或密钥失效，需要重新输入密码）", e);
            return "";
        }
    }


    private static SecretKey key(Context ctx) {
        if (sKeyStoreOk) {
            SecretKey k = keyStoreKey();
            if (k != null) return k;
            sKeyStoreOk = false;
            Log.w(TAG, "AndroidKeyStore 不可用，退回软件密钥（安全性降低）");
        }
        return fallbackKey(ctx);
    }

    private static SecretKey keyStoreKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) {
                java.security.Key k = ks.getKey(ALIAS, null);
                if (k instanceof SecretKey) return (SecretKey) k;
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        } catch (Throwable t) {
            Log.w(TAG, "KeyStore 初始化失败: " + t);
            return null;
        }
    }

    private static SecretKey fallbackKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("secret", Context.MODE_PRIVATE);
        String b64 = sp.getString(PREF_FALLBACK_KEY, null);
        byte[] raw;
        if (b64 == null) {
            raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            sp.edit().putString(PREF_FALLBACK_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        } else {
            try {
                raw = Base64.decode(b64, Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }
        return new SecretKeySpec(raw, "AES");
    }
}
