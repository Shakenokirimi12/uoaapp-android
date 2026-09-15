package com.shakenokirimi12.uoa_app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 設定を JSON ファイルへ書き出す / 読み戻す (SAF で選んだ Uri)。
 * 対象は PreferenceManager の平文 prefs ("uoa_app_prefs") のうち、端末固有でないユーザー設定と
 * 出席記録。以前は存在しない prefs 名 ("app_preferences") を読んでいて、常に "{}" (2 バイト) しか
 * 書き出せていなかった (2026-09-15 に発覚)。
 *
 * パスワード・セッション cookie・FCM トークン・device_id は入れない。平文ファイルに置く価値より
 * 漏れたときの損害が大きく、端末固有の値は他端末へ持ち込んでも意味が無い。ユーザー名だけは入れる。
 */
public class BackupService {

    private static final String TAG = "BackupService";
    private static final int FORMAT_VERSION = 2;

    /** PreferenceManager.PREF_NAME と同じ。 */
    private static final String PREFS_NAME = "uoa_app_prefs";
    private static final String ATTENDANCE_PREFS_NAME = "attendance_data_v2";

    private static final String[] SETTINGS_KEYS = {
            "onboarding_done",
            "notify_assignments", "notify_grades", "notify_lunch", "lunch_notify_time",
            "selected_notify_times", "custom_notify_times",
            "bg_notify_change", "bg_notify_success", "bg_notify_failure", "bg_notify_nochange",
            "sync_interval", "auto_attendance_enabled",
            "main_tabs", "other_tabs",
            "has_seen_idp_tutorial", "otp_auto_fetch_enabled",
            "review_consent_given", "review_guidelines_accepted",
    };

    public static void exportTo(Context ctx, Uri uri) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        JsonObject backup = new JsonObject();
        backup.addProperty("_version", FORMAT_VERSION);
        backup.addProperty("username", PreferenceManager.getInstance(ctx).getUsername());

        for (String key : SETTINGS_KEYS) {
            Object val = all.get(key);
            if (val instanceof String) backup.addProperty(key, (String) val);
            else if (val instanceof Boolean) backup.addProperty(key, (Boolean) val);
            else if (val instanceof Integer) backup.addProperty(key, (Integer) val);
            else if (val instanceof Long) backup.addProperty(key, (Long) val);
            else if (val instanceof Float) backup.addProperty(key, (Float) val);
        }

        SharedPreferences attendancePrefs = ctx.getSharedPreferences(ATTENDANCE_PREFS_NAME, Context.MODE_PRIVATE);
        String attendanceJson = attendancePrefs.getString("data", null);
        if (attendanceJson != null) {
            backup.addProperty("_attendance_data_v2", attendanceJson);
        }

        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new Exception("ファイルを開けませんでした");
            os.write(new Gson().toJson(backup).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        Log.d(TAG, "Backup exported: " + backup.size() + " keys");
    }

    public static void importFrom(Context ctx, Uri uri) throws Exception {
        String json;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("ファイルを開けませんでした");
            // available() はストリーム全長を保証しない (SAF 経由だと 0 のことがある)。最後まで読む。
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            json = buf.toString(StandardCharsets.UTF_8.name());
        }

        JsonObject backup = JsonParser.parseString(json).getAsJsonObject();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> current = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();

        for (String key : SETTINGS_KEYS) {
            if (!backup.has(key)) continue;
            JsonElement elem = backup.get(key);
            if (!elem.isJsonPrimitive()) continue;
            // 既存の値の型に合わせる。int を float で書き戻すと getInt が ClassCastException で落ちる。
            Object existing = current.get(key);
            if (elem.getAsJsonPrimitive().isBoolean()) {
                editor.putBoolean(key, elem.getAsBoolean());
            } else if (elem.getAsJsonPrimitive().isNumber()) {
                if (existing instanceof Long) editor.putLong(key, elem.getAsLong());
                else if (existing instanceof Float) editor.putFloat(key, elem.getAsFloat());
                else editor.putInt(key, elem.getAsInt());
            } else {
                editor.putString(key, elem.getAsString());
            }
        }
        editor.apply();

        if (backup.has("username")) {
            String username = backup.get("username").getAsString();
            if (!username.isEmpty()) PreferenceManager.getInstance(ctx).setUsername(username);
        }

        if (backup.has("_attendance_data_v2")) {
            ctx.getSharedPreferences(ATTENDANCE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("data", backup.get("_attendance_data_v2").getAsString())
                    .apply();
        }
        Log.d(TAG, "Backup imported");
    }
}
