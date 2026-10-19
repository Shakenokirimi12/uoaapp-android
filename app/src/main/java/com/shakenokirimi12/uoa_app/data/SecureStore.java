package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 資格情報やセッション cookie を Android Keystore の AES-GCM 鍵で暗号化して SharedPreferences に置く。
 * 非推奨になった androidx.security:security-crypto (EncryptedSharedPreferences) の置き換え。
 *
 * 値は base64(IV || 暗号文+タグ)。キー名を AAD に入れて、別のキーの値へ差し替えられても
 * 復号に失敗するようにしている。キー名そのものは秘密ではないので平文のまま。
 *
 * 鍵は端末から持ち出せないので、この prefs ファイルはバックアップ対象から外している
 * (backup_rules / data_extraction_rules)。別端末に復元されても復号できず、空として扱う。
 */
final class SecureStore {
    private static final String TAG = "SecureStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;
    private final String keyAlias;
    // Keystore の復号は 1 回数 ms かかる。getPassword() などは同期のたびに何度も呼ばれるので覚えておく。
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    SecureStore(@NonNull Context context, @NonNull String prefName, @NonNull String keyAlias) {
        this.prefs = context.getApplicationContext().getSharedPreferences(prefName, Context.MODE_PRIVATE);
        this.keyAlias = keyAlias;
    }

    /** 無い・復号できないときは def。復号できない値は二度と読めないので消しておく。 */
    @NonNull
    String getString(@NonNull String key, @NonNull String def) {
        String cached = cache.get(key);
        if (cached != null) return cached;
        String stored = prefs.getString(key, null);
        if (stored == null) return def;
        String plain = decrypt(key, stored);
        if (plain == null) {
            prefs.edit().remove(key).apply();
            return def;
        }
        cache.put(key, plain);
        return plain;
    }

    boolean contains(@NonNull String key) {
        return prefs.contains(key);
    }

    void putString(@NonNull String key, @Nullable String value) {
        if (value == null) {
            remove(key);
            return;
        }
        String encrypted = encrypt(key, value);
        if (encrypted == null) {
            // 鍵が使えない端末。平文で置くよりは保存しない方を選ぶ (次回ログインで入力し直しになる)。
            Log.e(TAG, "Keystore unavailable; not persisting " + key);
            cache.put(key, value);
            return;
        }
        prefs.edit().putString(key, encrypted).apply();
        cache.put(key, value);
    }

    void remove(@NonNull String key) {
        cache.remove(key);
        prefs.edit().remove(key).apply();
    }

    void clear() {
        cache.clear();
        prefs.edit().clear().apply();
    }

    @Nullable
    private String encrypt(String key, String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed", e);
            return null;
        }
    }

    @Nullable
    private String decrypt(String key, String stored) {
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            if (all.length <= IV_BYTES) return null;
            SecretKey secret = getExistingKey();
            if (secret == null) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secret,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(all, 0, IV_BYTES)));
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "decrypt failed for " + key, e);
            return null;
        }
    }

    @Nullable
    private SecretKey getExistingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(keyAlias, null);
        return entry instanceof KeyStore.SecretKeyEntry
                ? ((KeyStore.SecretKeyEntry) entry).getSecretKey()
                : null;
    }

    private synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = getExistingKey();
        if (existing != null) return existing;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
