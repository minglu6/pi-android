package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Connector token at rest: AES-GCM via Android Keystore.
 * Legacy plaintext {@code token} in {@code pi_bridge} prefs is migrated once then deleted.
 * Never logs the token value.
 */
public final class SecureTokenStore {
    private static final String TAG = "SecureTokenStore";
    private static final String PREF = "pi_bridge";
    /** Legacy plaintext key — removed after successful migrate. */
    public static final String K_PLAIN = "token";
    private static final String K_ENC = "token_enc";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pi_bridge_token_aes";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private SecureTokenStore() {}

    /** Active-profile token (legacy single-slot + migration). */
    @NonNull
    public static String get(@NonNull Context ctx) {
        ConnectionProfiles.ensureMigrated(ctx);
        return getForProfile(ctx, ConnectionProfiles.active(ctx).id);
    }

    public static void set(@NonNull Context ctx, @Nullable String value) {
        ConnectionProfiles.ensureMigrated(ctx);
        setForProfile(ctx, ConnectionProfiles.active(ctx).id, value);
    }

    @NonNull
    public static String getForProfile(@NonNull Context ctx, @NonNull String profileId) {
        SharedPreferences prefs = prefs(ctx);
        String key = encKey(profileId);
        String enc = prefs.getString(key, null);
        if (enc != null && !enc.isEmpty()) {
            try {
                String plain = decrypt(enc);
                return plain != null ? plain : "";
            } catch (Exception e) {
                Log.w(TAG, "token decrypt failed");
                return "";
            }
        }
        // Legacy single-slot (pre-profiles) — only for default local id.
        if (ConnectionProfiles.DEFAULT_ID.equals(profileId)) {
            String legacyEnc = prefs.getString(K_ENC, null);
            if (legacyEnc != null && !legacyEnc.isEmpty()) {
                try {
                    String plain = decrypt(legacyEnc);
                    if (plain != null && !plain.isEmpty()) {
                        setForProfile(ctx, profileId, plain);
                        prefs.edit().remove(K_ENC).remove(K_PLAIN).apply();
                        return plain;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "legacy token decrypt failed");
                }
            }
            String legacy = prefs.getString(K_PLAIN, "");
            if (legacy == null) legacy = "";
            if (!legacy.isEmpty()) {
                setForProfile(ctx, profileId, legacy);
                prefs.edit().remove(K_PLAIN).apply();
                return legacy;
            }
        }
        return "";
    }

    public static void setForProfile(@NonNull Context ctx, @NonNull String profileId,
                                     @Nullable String value) {
        String v = value == null ? "" : value.trim();
        SharedPreferences prefs = prefs(ctx);
        String key = encKey(profileId);
        if (v.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            String packed = encrypt(v);
            prefs.edit().putString(key, packed).apply();
        } catch (Exception e) {
            Log.w(TAG, "token encrypt failed; falling back to prefs (no value logged)");
            prefs.edit().putString(key + "_plain", v).apply();
        }
    }

    @NonNull
    private static String encKey(@NonNull String profileId) {
        return K_ENC + "_" + profileId;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @NonNull
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    @NonNull
    private static String encrypt(@NonNull String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalStateException("unexpected GCM IV length");
        }
        byte[] ciphertext = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        buf.put((byte) iv.length);
        buf.put(iv);
        buf.put(ciphertext);
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP);
    }

    @Nullable
    private static String decrypt(@NonNull String packed) throws Exception {
        byte[] raw = Base64.decode(packed, Base64.DEFAULT);
        if (raw.length < 2) return null;
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int ivLen = buf.get() & 0xff;
        if (ivLen != IV_BYTES || buf.remaining() < ivLen + 1) return null;
        byte[] iv = new byte[ivLen];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }
}
