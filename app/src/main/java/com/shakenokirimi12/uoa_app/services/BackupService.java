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
 * ユーザー名・パスワード・セッション cookie・FCM トークン・device_id は入れない。平文ファイルに置く価値より
 * 漏れたときの損害が大きく、端末固有の値 (PreferenceManager.DEVICE_LOCAL_KEYS) は他端末へ持ち込むと
 * 2 台が同じ id で登録してプッシュを取り合う。
 * 以前はユーザー名だけ入れて読み戻していた。別のアカウントでログインしている端末に戻すと、ユーザー名だけが
 * 入れ替わって今のパスワードと組になり、他人のアカウントへ誤ったパスワードでログインを試み続ける
 * (大学側でロックされうる)。資格情報はログインし直して入れる。古いファイルにユーザー名があっても読まない。
 * 読み戻しも SETTINGS_KEYS だけを書くので、ファイルに device_id などが入っていても端末の値は変わらない。
 */
public class BackupService {

    private static final String TAG = "BackupService";
    private static final int FORMAT_VERSION = 3;

    private static final String PREFS_NAME = PreferenceManager.PREF_NAME;
    private static final String ATTENDANCE_PREFS_NAME = "attendance_data_v2";

    static final String[] SETTINGS_KEYS = {
            "onboarding_done",
            "notify_assignments", "notify_lunch", "lunch_notify_time",
            "selected_notify_times", "custom_notify_times",
            "bg_notify_change", "bg_notify_success", "bg_notify_failure", "bg_notify_nochange",
            "sync_interval", "auto_attendance_enabled",
            "main_tabs", "other_tabs",
            "has_seen_idp_tutorial",
    };

    public static void exportTo(Context ctx, Uri uri) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences attendancePrefs = ctx.getSharedPreferences(ATTENDANCE_PREFS_NAME, Context.MODE_PRIVATE);
        JsonObject backup = buildBackup(prefs.getAll(), attendancePrefs.getString("data", null));

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
        SharedPreferences.Editor editor = prefs.edit();
        applySettings(backup, prefs.getAll(), editor);
        editor.apply();
        // 学食通知の ON/OFF と時刻を戻したので、予約もそれに合わせて作り直す。
        com.shakenokirimi12.uoa_app.services.notification.LunchMenuWorker.reschedule(ctx);

        if (backup.has("_attendance_data_v2")) {
            ctx.getSharedPreferences(ATTENDANCE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("data", backup.get("_attendance_data_v2").getAsString())
                    .apply();
        }
        Log.d(TAG, "Backup imported");
    }

    /** 書き出す JSON。SETTINGS_KEYS だけを拾うので、端末固有の値は prefs にあっても入らない。 */
    static JsonObject buildBackup(Map<String, ?> all, String attendanceJson) {
        JsonObject backup = new JsonObject();
        backup.addProperty("_version", FORMAT_VERSION);
        for (String key : SETTINGS_KEYS) {
            Object val = all.get(key);
            if (val instanceof String) backup.addProperty(key, (String) val);
            else if (val instanceof Boolean) backup.addProperty(key, (Boolean) val);
            else if (val instanceof Integer) backup.addProperty(key, (Integer) val);
            else if (val instanceof Long) backup.addProperty(key, (Long) val);
            else if (val instanceof Float) backup.addProperty(key, (Float) val);
        }
        if (attendanceJson != null) {
            backup.addProperty("_attendance_data_v2", attendanceJson);
        }
        return backup;
    }

    /**
     * 読み戻す。SETTINGS_KEYS だけを書く。ファイルに device_id / fcm_token があっても (手で足された、
     * 別の版が書いた等) 無視し、この端末の値を上書きしない。
     */
    static void applySettings(JsonObject backup, Map<String, ?> current, SharedPreferences.Editor editor) {
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
    }
}
