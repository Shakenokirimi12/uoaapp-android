package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** 実機の Keystore で SecureStore の暗号化と、EncryptedSharedPreferences からの移行を確かめる。 */
@RunWith(AndroidJUnit4.class)
@SuppressWarnings("deprecation")
public class SecureStorageInstrumentedTest {
    private static final String STORE = "test_secure_store";
    private static final String ALIAS = "test_secure_store_key";
    private Context ctx;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // deleteSharedPreferences はファイルを消すだけで、プロセス内に読み込み済みの値は残る。先に空にする。
        for (String name : new String[] { STORE, "uoa_app_secure_prefs" }) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            ctx.deleteSharedPreferences(name);
        }
    }

    @Test
    public void roundTrip_andNothingStoredInPlaintext() {
        SecureStore s = new SecureStore(ctx, STORE, ALIAS);
        s.putString("password", "hunter2-パスワード");
        // 新しいインスタンス (メモリのキャッシュ無し) から Keystore で復号できる
        assertEquals("hunter2-パスワード", new SecureStore(ctx, STORE, ALIAS).getString("password", ""));
        String raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("password", "");
        assertFalse(raw.isEmpty());
        assertFalse(raw.contains("hunter2"));
    }

    @Test
    public void valueMovedToAnotherKey_doesNotDecrypt() {
        SecureStore s = new SecureStore(ctx, STORE, ALIAS);
        s.putString("password", "secret");
        SharedPreferences raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        raw.edit().putString("username", raw.getString("password", "")).commit();
        assertEquals("", new SecureStore(ctx, STORE, ALIAS).getString("username", ""));
    }

    @Test
    public void migratesFromEncryptedSharedPreferences_andDeletesLegacyFile() throws Exception {
        SharedPreferences legacy = EncryptedSharedPreferences.create(
                "uoa_app_secure_prefs",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        legacy.edit().putString("username", "s1300000").putString("password", "old-pass").commit();
        File legacyFile = new File(ctx.getApplicationInfo().dataDir, "shared_prefs/uoa_app_secure_prefs.xml");
        assertTrue(legacyFile.exists());

        SecureStore target = new SecureStore(ctx, STORE, ALIAS);
        LegacyEncryptedPrefsMigration.migrateIfNeeded(ctx, target);

        SecureStore reopened = new SecureStore(ctx, STORE, ALIAS);
        assertEquals("s1300000", reopened.getString("username", ""));
        assertEquals("old-pass", reopened.getString("password", ""));
        assertFalse(legacyFile.exists());
        assertNotEquals("old-pass", ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("password", ""));
    }
}
