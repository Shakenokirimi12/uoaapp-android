package com.shakenokirimi12.uoa_app.services.calendar;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 時間割を端末のカレンダー (Google カレンダーなど CalendarContract を読むアプリ) に出す。
 *
 * このアプリ専用のアカウント (CalendarAccountService) を端末に登録し、その下にカレンダーを 1 つ作って
 * 中身を毎回時間割のキャッシュで置き換える。Google アカウントのカレンダーには書かないので
 * クラウドへは上がらず、オプトアウトすればアカウントごと消えて跡が残らない。
 *
 * 最初は ACCOUNT_TYPE_LOCAL で作っていたが、Google カレンダーアプリには表示されなかった。
 * Google カレンダーは AccountManager に登録されたアカウントのカレンダーしか出さないため。
 *
 * カレンダーは読み取り専用 (CAL_ACCESS_READ) にしている。カレンダーアプリ側で編集されても
 * 次の同期で上書きされて消えるだけなので、最初から編集させない。
 */
public final class DeviceCalendarSync {
    private static final String TAG = "DeviceCalendarSync";
    private static final String ACCOUNT_NAME = "UoA非公式アプリ";
    /** res/xml/calendar_authenticator.xml と calendar_syncadapter.xml の accountType と同じ。 */
    static final String ACCOUNT_TYPE = "com.shakenokirimi12.uoa_app.calendar";
    private static final String CALENDAR_NAME = "uoa_timetable";
    private static final String DISPLAY_NAME = "UoA 時間割";
    private static final int COLOR = 0xFF1E6FD9;
    private static final String TIME_ZONE = "Asia/Tokyo";
    // 同じ内容なら書き直さない。カレンダーアプリ側の再描画や通知の揺れを避けるため。
    private static final String STATE_PREFS = "device_calendar_sync";
    private static final String KEY_LAST_HASH = "last_hash";

    // CalendarProvider への書き込みは数百件で数百 ms かかる。UI スレッドから外し、順番に流す。
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DeviceCalendarSync() {}

    public static final String[] PERMISSIONS = {
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR };

    public static boolean hasPermission(@NonNull Context ctx) {
        for (String p : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** 時間割を取り直したあとに呼ぶ。オプトインしていない・権限が無いときは何もしない。 */
    public static void syncAsync(@NonNull Context context, @NonNull List<CalendarEvent> events) {
        Context ctx = context.getApplicationContext();
        List<CalendarEvent> copy = new ArrayList<>(events);
        executor.execute(() -> {
            try {
                sync(ctx, copy, false);
            } catch (Exception e) {
                Log.w(TAG, "sync failed", e);
            }
        });
    }

    /** オプトインした直後に、今あるキャッシュで書き出す。 */
    public static void syncFromCacheAsync(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                sync(ctx, DataCache.getInstance(ctx).loadEvents(), true);
            } catch (Exception e) {
                Log.w(TAG, "sync failed", e);
            }
        });
    }

    /** オプトアウト・ログアウトのとき。作ったカレンダーを中身ごと消す。 */
    public static void removeAsync(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                remove(ctx);
            } catch (Exception e) {
                Log.w(TAG, "remove failed", e);
            }
        });
    }

    private static void sync(Context ctx, List<CalendarEvent> events, boolean force) throws Exception {
        if (!PreferenceManager.getInstance(ctx).isDeviceCalendarSyncEnabled() || !hasPermission(ctx)) return;

        int hash = hash(events);
        android.content.SharedPreferences state = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        ensureAccount(ctx);
        long calendarId = findCalendarId(ctx);
        if (!force && calendarId >= 0 && state.getInt(KEY_LAST_HASH, 0) == hash) return;
        if (calendarId < 0) calendarId = createCalendar(ctx);
        if (calendarId < 0) return;

        ContentResolver cr = ctx.getContentResolver();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        // sync adapter として消すと「削除済み」の印を残さずに行ごと消える。
        ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI))
                .withSelection(CalendarContract.Events.CALENDAR_ID + "=?", new String[] { String.valueOf(calendarId) })
                .build());
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null || e.getDtend() == null) continue;
            ops.add(ContentProviderOperation.newInsert(asSyncAdapter(CalendarContract.Events.CONTENT_URI))
                    .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                    .withValue(CalendarContract.Events.TITLE, e.getSummary())
                    .withValue(CalendarContract.Events.EVENT_LOCATION, e.getLocation())
                    .withValue(CalendarContract.Events.DTSTART, e.getDtstart().getTime())
                    .withValue(CalendarContract.Events.DTEND, e.getDtend().getTime())
                    .withValue(CalendarContract.Events.EVENT_TIMEZONE, TIME_ZONE)
                    // 授業前の通知はアプリ側で出している。カレンダーアプリからも鳴ると二重になる。
                    .withValue(CalendarContract.Events.HAS_ALARM, 0)
                    .withValue(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                    .build());
        }
        cr.applyBatch(CalendarContract.AUTHORITY, ops);
        state.edit().putInt(KEY_LAST_HASH, hash).apply();
        Log.d(TAG, "Wrote " + (ops.size() - 1) + " events to device calendar");
    }

    private static void remove(Context ctx) {
        ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        if (hasPermission(ctx)) {
            ctx.getContentResolver().delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                    CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                    new String[] { ACCOUNT_NAME, ACCOUNT_TYPE });
            deleteLegacyLocalCalendar(ctx);
        }
        // アカウントを消すと、カレンダープロバイダ側も紐づく行を片付ける。権限が無くても消せる。
        AccountManager.get(ctx).removeAccountExplicitly(account());
    }

    private static Account account() {
        return new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
    }

    /** 自前の種別のアカウントは権限なしで追加できる。既にあれば何もしない。 */
    private static void ensureAccount(Context ctx) {
        AccountManager am = AccountManager.get(ctx);
        for (Account a : am.getAccountsByType(ACCOUNT_TYPE)) {
            if (ACCOUNT_NAME.equals(a.name)) return;
        }
        if (am.addAccountExplicitly(account(), null, null)) {
            try {
                ContentResolver.setIsSyncable(account(), CalendarContract.AUTHORITY, 1);
                ContentResolver.setSyncAutomatically(account(), CalendarContract.AUTHORITY, true);
            } catch (SecurityException e) {
                // 同期設定が書けなくてもカレンダー自体は書ける。表示に要るのはアカウントの存在の方。
                Log.w(TAG, "Could not update sync settings", e);
            }
        }
        // ACCOUNT_TYPE_LOCAL で作っていた頃のカレンダーが残っていると、表示できる端末では二重に出る。
        deleteLegacyLocalCalendar(ctx);
    }

    private static void deleteLegacyLocalCalendar(Context ctx) {
        Uri legacy = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        ctx.getContentResolver().delete(legacy,
                CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                new String[] { ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL });
    }

    private static long findCalendarId(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[] { CalendarContract.Calendars._ID },
                CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=? AND "
                        + CalendarContract.Calendars.NAME + "=?",
                new String[] { ACCOUNT_NAME, ACCOUNT_TYPE, CALENDAR_NAME }, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        return -1;
    }

    private static long createCalendar(Context ctx) {
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
        v.put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE);
        v.put(CalendarContract.Calendars.NAME, CALENDAR_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, DISPLAY_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_COLOR, COLOR);
        v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ);
        v.put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TIME_ZONE);
        v.put(CalendarContract.Calendars.VISIBLE, 1);
        v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        Uri uri = ctx.getContentResolver().insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v);
        if (uri == null) return -1;
        try {
            return Long.parseLong(uri.getLastPathSegment());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .build();
    }

    private static int hash(List<CalendarEvent> events) {
        int h = 1;
        for (CalendarEvent e : events) {
            h = 31 * h + java.util.Objects.hash(e.getSummary(), e.getLocation(),
                    e.getDtstart() == null ? 0 : e.getDtstart().getTime(),
                    e.getDtend() == null ? 0 : e.getDtend().getTime());
        }
        return h;
    }
}
