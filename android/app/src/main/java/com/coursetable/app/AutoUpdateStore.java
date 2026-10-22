package com.coursetable.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AutoUpdateStore {
    private static final String PREFS = "auto_update";
    private static final String KEY_ALIAS = "coursetable_auto_update_v1";
    private static final String K_CONFIG = "config_enc";
    private static final String K_ENABLED = "enabled";
    private static final String K_INTERVAL = "interval_days";
    private static final String K_LAST_AT = "last_at";
    private static final String K_LAST_ATTEMPT = "last_attempt";
    private static final String K_LAST_ERROR = "last_error";
    private static final String K_PENDING = "pending_result";
    private static final String K_PENDING_AT = "pending_at";

    private AutoUpdateStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject config(Context context) {
        String raw = prefs(context).getString(K_CONFIG, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            String json = decrypt(raw);
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void saveConfig(Context context, JSONObject config) throws Exception {
        prefs(context).edit().putString(K_CONFIG, encrypt(config.toString())).apply();
    }

    static boolean hasConfig(Context context) {
        return config(context) != null;
    }

    static void clear(Context context) {
        prefs(context).edit()
                .remove(K_CONFIG)
                .remove(K_PENDING)
                .remove(K_PENDING_AT)
                .remove(K_LAST_AT)
                .remove(K_LAST_ATTEMPT)
                .remove(K_LAST_ERROR)
                .putBoolean(K_ENABLED, false)
                .apply();
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(K_ENABLED, false) && hasConfig(context);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).apply();
    }

    static int intervalDays(Context context) {
        int value = prefs(context).getInt(K_INTERVAL, 1);
        return Math.max(1, Math.min(30, value));
    }

    static void setIntervalDays(Context context, int days) {
        prefs(context).edit().putInt(K_INTERVAL, Math.max(1, Math.min(30, days))).apply();
    }

    static long lastAt(Context context) {
        return prefs(context).getLong(K_LAST_AT, 0L);
    }

    static long lastAttemptAt(Context context) {
        return prefs(context).getLong(K_LAST_ATTEMPT, 0L);
    }

    static String lastError(Context context) {
        return prefs(context).getString(K_LAST_ERROR, "");
    }

    static void recordSuccess(Context context, JSONObject result) {
        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putString(K_PENDING, result.toString())
                .putLong(K_PENDING_AT, now)
                .putLong(K_LAST_AT, now)
                .putLong(K_LAST_ATTEMPT, now)
                .putString(K_LAST_ERROR, "")
                .apply();
    }

    static void recordFailure(Context context, String error) {
        prefs(context).edit()
                .putLong(K_LAST_ATTEMPT, System.currentTimeMillis())
                .putString(K_LAST_ERROR, error == null ? "自动更新失败" : error)
                .apply();
    }

    static void markUpdated(Context context, String fetchedAt) {
        prefs(context).edit()
                .putLong(K_LAST_AT, System.currentTimeMillis())
                .putLong(K_LAST_ATTEMPT, System.currentTimeMillis())
                .putString(K_LAST_ERROR, "")
                .remove(K_PENDING)
                .remove(K_PENDING_AT)
                .apply();
    }

    static void ackPending(Context context, long pendingAt) {
        SharedPreferences pref = prefs(context);
        long stored = pref.getLong(K_PENDING_AT, 0L);
        if (pendingAt <= 0 || stored == pendingAt) {
            pref.edit().remove(K_PENDING).remove(K_PENDING_AT).apply();
        }
    }

    static JSONObject pending(Context context) {
        String raw = prefs(context).getString(K_PENDING, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JSONObject state(Context context) {
        JSONObject out = new JSONObject();
        try {
            boolean has = hasConfig(context);
            boolean on = prefs(context).getBoolean(K_ENABLED, false) && has;
            int interval = intervalDays(context);
            long now = System.currentTimeMillis();
            long last = lastAt(context);
            long attempt = lastAttemptAt(context);
            long gap = interval * 86400000L;
            boolean due = on && (last == 0L || now - last >= gap)
                    && (attempt == 0L || now - attempt >= 600000L);
            out.put("enabled", on);
            out.put("hasConfig", has);
            out.put("intervalDays", interval);
            out.put("lastAt", last);
            out.put("lastAttemptAt", attempt);
            out.put("lastError", lastError(context));
            out.put("due", due);
            out.put("pendingAt", prefs(context).getLong(K_PENDING_AT, 0L));
            String pending = prefs(context).getString(K_PENDING, "");
            if (pending != null && !pending.isEmpty()) {
                try {
                    out.put("pending", new JSONObject(pending));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey secret = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (secret != null) return secret;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("bad data");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }
}
