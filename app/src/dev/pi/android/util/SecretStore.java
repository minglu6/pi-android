package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM + Android Keystore 存敏感字段（连接器 token、SSH 密码、私钥 PEM）。
 * 不依赖 security-crypto，适配无 Gradle 构建。
 */
public final class SecretStore {
    private static final String PREF = "pi_secrets_enc";
    private static final String KEY_ALIAS = "pi_android_secret_aes";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG = 128;

    private SecretStore() {}

    public static void put(@NonNull Context ctx, @NonNull String key, @Nullable String value) {
        SharedPreferences p = prefs(ctx);
        if (value == null || value.isEmpty()) {
            p.edit().remove(key).apply();
            return;
        }
        try {
            byte[] enc = encrypt(ctx, value.getBytes(StandardCharsets.UTF_8));
            p.edit().putString(key, Base64.encodeToString(enc, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("encrypt secret " + key, e);
        }
    }

    @Nullable
    public static String get(@NonNull Context ctx, @NonNull String key) {
        String b64 = prefs(ctx).getString(key, null);
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
            byte[] plain = decrypt(ctx, raw);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void remove(@NonNull Context ctx, @NonNull String key) {
        prefs(ctx).edit().remove(key).apply();
    }

    public static String tokenKey(String nodeId) { return "token:" + nodeId; }
    public static String sshPasswordKey(String nodeId) { return "ssh_pw:" + nodeId; }
    public static String sshPrivateKeyKey(String nodeId) { return "ssh_key:" + nodeId; }
    public static String sshPublicKeyKey(String nodeId) { return "ssh_pub:" + nodeId; }
    public static String hostKeyFpKey(String nodeId) { return "host_fp:" + nodeId; }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SecretKey key(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private static byte[] encrypt(Context ctx, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(ctx));
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        ByteBuffer buf = ByteBuffer.allocate(4 + iv.length + ct.length);
        buf.putInt(iv.length);
        buf.put(iv);
        buf.put(ct);
        return buf.array();
    }

    private static byte[] decrypt(Context ctx, byte[] blob) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(blob);
        int ivLen = buf.getInt();
        byte[] iv = new byte[ivLen];
        buf.get(iv);
        byte[] ct = new byte[buf.remaining()];
        buf.get(ct);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(ctx), new GCMParameterSpec(GCM_TAG, iv));
        return c.doFinal(ct);
    }
}
