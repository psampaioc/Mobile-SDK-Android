package com.dji.sdk.sample.rtk;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local profile storage. Only the password is encrypted; no caller receives it for display. */
public final class NetworkRtkProfileStore {
    private static final String FILE = "network_rtk_profile";
    private static final String KEY_ALIAS = "matrice_network_rtk_secret";
    private final SharedPreferences preferences;

    public NetworkRtkProfileStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Nullable public NetworkRtkProfile load() {
        String host = preferences.getString("host", "");
        String mountpoint = preferences.getString("mountpoint", "");
        String password = decrypt(preferences.getString("password", ""));
        if (host == null || mountpoint == null || password == null) return null;
        return new NetworkRtkProfile(host, preferences.getInt("port", 2101),
                value("user"), password, mountpoint);
    }

    public boolean save(@NonNull NetworkRtkProfile profile) {
        if (!profile.isValid()) return false;
        String encrypted = encrypt(profile.password);
        if (encrypted == null) return false;
        return preferences.edit().putString("host", profile.host).putInt("port", profile.port)
                .putString("user", profile.user).putString("mountpoint", profile.mountpoint)
                .putString("password", encrypted).commit();
    }

    @NonNull private String value(@NonNull String key) {
        String value = preferences.getString(key, ""); return value == null ? "" : value;
    }

    @Nullable private static String encrypt(@NonNull String text) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Let AndroidKeyStore generate its own random nonce. This is required on devices
            // that reject caller-provided GCM IVs unless the key expressly permits them.
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length == 0) return null;
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception ignored) { return null; }
    }

    @Nullable private static String decrypt(@Nullable String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            if (combined.length < 13) return null;
            byte[] iv = new byte[12]; System.arraycopy(combined, 0, iv, 0, iv.length);
            byte[] data = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, data, 0, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }

    @NonNull private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
