package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.File;
import java.security.KeyStore;
import java.util.Map;

/**
 * 旧版が EncryptedSharedPreferences に置いていた値を SecureStore へ一度だけ移す。
 *
 * security-crypto はこの読み出しのためだけに残している。旧版から更新する利用者が
 * いなくなったら (2.0 正式版から数リリース後を目安に)、このクラスと依存ごと消してよい。
 * 消した後に旧版から直接更新した人は、ログインし直しになるだけで済む。
 */
@SuppressWarnings("deprecation")
final class LegacyEncryptedPrefsMigration {
    private static final String TAG = "LegacyPrefsMigration";
    private static final String LEGACY_PREF_NAME = "uoa_app_secure_prefs";
    // EncryptedSharedPreferences が鍵セットを置く prefs と、MasterKeys の Keystore 別名 (ライブラリの既定値)。
    private static final String LEGACY_KEYSET_PREF_NAME = "__androidx_security_crypto_encrypted_prefs__";
    private static final String LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_";

    private LegacyEncryptedPrefsMigration() {}

    static void migrateIfNeeded(@NonNull Context context, @NonNull SecureStore target) {
        Context app = context.getApplicationContext();
        File legacyFile = new File(app.getApplicationInfo().dataDir, "shared_prefs/" + LEGACY_PREF_NAME + ".xml");
        if (!legacyFile.exists()) return;

        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences legacy = EncryptedSharedPreferences.create(
                    LEGACY_PREF_NAME,
                    masterKeyAlias,
                    app,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            int moved = 0;
            for (Map.Entry<String, ?> e : legacy.getAll().entrySet()) {
                // 旧版が置いていたのは文字列だけ。新しい側に既にある値 (移行途中で落ちた場合) は上書きしない。
                if (e.getValue() instanceof String && !target.contains(e.getKey())) {
                    target.putString(e.getKey(), (String) e.getValue());
                    moved++;
                }
            }
            Log.i(TAG, "Migrated " + moved + " entries from EncryptedSharedPreferences");
        } catch (Exception e) {
            // 読めないものは移しようがない。消してログインし直してもらう。
            Log.w(TAG, "Could not read legacy encrypted prefs; dropping them", e);
        }

        app.deleteSharedPreferences(LEGACY_PREF_NAME);
        app.deleteSharedPreferences(LEGACY_KEYSET_PREF_NAME);
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(LEGACY_MASTER_KEY_ALIAS)) ks.deleteEntry(LEGACY_MASTER_KEY_ALIAS);
        } catch (Exception e) {
            Log.w(TAG, "Could not delete legacy master key", e);
        }
    }
}
