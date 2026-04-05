package com.shakenokirimi12.uoa_app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Backup/restore settings to a local JSON file.
 * For full Google Drive integration the user would use SAF (Storage Access Framework)
 * to pick a file location, then call exportTo/importFrom with the resulting Uri.
 */
public class BackupService {

    private static final String TAG = "BackupService";

    private static final String[] SETTINGS_KEYS = {
            "username", "password", "has_onboarded", "skip_moodle",
            "notify_enabled", "fetch_interval",
            "auto_attendance_enabled", "geofence_radius", "geofence_lat", "geofence_lng",
            "device_id"
    };

    private static final String PREFS_NAME = "app_preferences";

    public static void exportTo(Context ctx, Uri uri) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JsonObject backup = new JsonObject();

        for (String key : SETTINGS_KEYS) {
            if (prefs.contains(key)) {
                Object val = prefs.getAll().get(key);
                if (val instanceof String) backup.addProperty(key, (String) val);
                else if (val instanceof Boolean) backup.addProperty(key, (Boolean) val);
                else if (val instanceof Number) backup.addProperty(key, (Number) val);
            }
        }

        // Also export attendance data
        SharedPreferences attendancePrefs = ctx.getSharedPreferences("attendance_data_v2", Context.MODE_PRIVATE);
        String attendanceJson = attendancePrefs.getString("data", null);
        if (attendanceJson != null) {
            backup.addProperty("_attendance_data_v2", attendanceJson);
        }

        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            if (os != null) {
                os.write(new Gson().toJson(backup).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
        Log.d(TAG, "Backup exported successfully");
    }

    public static void importFrom(Context ctx, Uri uri) throws Exception {
        String json;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("ファイルを開けませんでした");
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            json = new String(bytes, StandardCharsets.UTF_8);
        }

        JsonObject backup = JsonParser.parseString(json).getAsJsonObject();
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();

        for (String key : SETTINGS_KEYS) {
            if (backup.has(key)) {
                com.google.gson.JsonElement elem = backup.get(key);
                if (elem.isJsonPrimitive()) {
                    if (elem.getAsJsonPrimitive().isBoolean()) {
                        editor.putBoolean(key, elem.getAsBoolean());
                    } else if (elem.getAsJsonPrimitive().isNumber()) {
                        editor.putFloat(key, elem.getAsFloat());
                    } else {
                        editor.putString(key, elem.getAsString());
                    }
                }
            }
        }
        editor.apply();

        if (backup.has("_attendance_data_v2")) {
            ctx.getSharedPreferences("attendance_data_v2", Context.MODE_PRIVATE)
                    .edit()
                    .putString("data", backup.get("_attendance_data_v2").getAsString())
                    .apply();
        }
        Log.d(TAG, "Backup imported successfully");
    }
}
