package com.dsh.android.util;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM encryption of small secrets (API key) backed by Android Keystore. */
public final class SecretStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dsh_master_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final android.content.SharedPreferences prefs;

    public SecretStore(Context ctx) {
        prefs = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE);
        try {
            ensureKey();
        } catch (Exception ignored) {
            // KeyStore unavailable on unusual devices; get() falls back to plain: prefixed values.
        }
    }

    private void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        kg.generateKey();
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    public void put(String name, String value) {
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            String payload = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            prefs.edit().putString(name, payload).apply();
        } catch (Exception e) {
            prefs.edit().putString(name, "plain:" + value).apply();
        }
    }

    public String get(String name) {
        String payload = prefs.getString(name, null);
        if (payload == null) return null;
        try {
            if (payload.startsWith("plain:")) return payload.substring(6);
            String[] parts = payload.split(":", 2);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}