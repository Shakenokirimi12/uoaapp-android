package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 端末固有の値 (device_id / fcm_token / server_holds_assignment_reminders) をバックアップで別端末へ持ち込まない。
 * 持ち込むと 2 台が同じ device_id で登録し、プッシュと課題リマインダーの予約を取り合う。
 */
public class BackupServiceTest {

    /** 書かれた値と消されたキーを覚えるだけの Editor。 */
    private static final class RecordingEditor implements SharedPreferences.Editor {
        final Map<String, Object> written = new HashMap<>();
        final Set<String> removed = new HashSet<>();
        @Override public SharedPreferences.Editor putString(String k, String v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor putStringSet(String k, Set<String> v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor putInt(String k, int v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor putLong(String k, long v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor putFloat(String k, float v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor putBoolean(String k, boolean v) { written.put(k, v); return this; }
        @Override public SharedPreferences.Editor remove(String k) { removed.add(k); return this; }
        @Override public SharedPreferences.Editor clear() { throw new AssertionError("clear() must not be called"); }
        @Override public boolean commit() { return true; }
        @Override public void apply() {}
    }

    @Test
    public void export_neverContainsDeviceLocalValues_evenWhenPrefsHoldThem() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("device_id", "11111111-aaaa");
        prefs.put("fcm_token", "token-of-this-phone");
        prefs.put("server_holds_assignment_reminders", true);
        prefs.put("notify_lunch", true);

        JsonObject backup = BackupService.buildBackup(prefs, "s1300000", null);

        for (String key : PreferenceManager.DEVICE_LOCAL_KEYS) assertFalse(key, backup.has(key));
        assertTrue(backup.get("notify_lunch").getAsBoolean());
    }

    @Test
    public void import_neverOverwritesDeviceLocalValues_evenWhenTheFileHasThem() {
        JsonObject file = new JsonObject();
        file.addProperty("device_id", "id-of-another-phone");
        file.addProperty("fcm_token", "token-of-another-phone");
        file.addProperty("server_holds_assignment_reminders", true);
        file.addProperty("notify_lunch", true);
        RecordingEditor editor = new RecordingEditor();

        BackupService.applySettings(file, new HashMap<>(), editor);

        for (String key : PreferenceManager.DEVICE_LOCAL_KEYS) assertFalse(key, editor.written.containsKey(key));
        assertEquals(true, editor.written.get("notify_lunch"));
    }

    @Test
    public void settingsKeys_andDeviceLocalKeys_doNotOverlap() {
        List<String> settings = Arrays.asList(BackupService.SETTINGS_KEYS);
        for (String key : PreferenceManager.DEVICE_LOCAL_KEYS) assertFalse(key, settings.contains(key));
    }

    @Test
    public void osRestore_dropsDeviceLocalValues_andKeepsSettings() {
        Set<String> restored = new HashSet<>(Arrays.asList(
                "device_id", "fcm_token", "server_holds_assignment_reminders", "notify_lunch", "main_tabs"));
        RecordingEditor editor = new RecordingEditor();

        int removed = UoaBackupAgent.removeDeviceLocal(restored, editor);

        assertEquals(3, removed);
        assertEquals(new HashSet<>(Arrays.asList(PreferenceManager.DEVICE_LOCAL_KEYS)), editor.removed);
        assertTrue(editor.written.isEmpty());
    }
}
