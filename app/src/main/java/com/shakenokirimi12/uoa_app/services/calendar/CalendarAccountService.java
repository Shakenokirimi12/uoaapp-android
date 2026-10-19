package com.shakenokirimi12.uoa_app.services.calendar;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;

/**
 * 端末カレンダー用アカウントの認証器と同期アダプタ。どちらも中身の無いスタブ。
 *
 * Google カレンダーアプリは AccountManager に登録されたアカウントのカレンダーしか出さず、
 * ACCOUNT_TYPE_LOCAL で作ったカレンダーは表示されなかった (2026-09-27 に報告)。
 * そこでこのアプリ専用のアカウント種別を登録し、その下にカレンダーを作る (ICSx⁵ などと同じ方法)。
 * 実際の書き込みは DeviceCalendarSync が時間割の取得のたびに行うので、ここでは何もしない。
 */
public final class CalendarAccountService {
    private CalendarAccountService() {}

    public static class AuthenticatorService extends Service {
        private Authenticator authenticator;

        @Override
        public void onCreate() {
            authenticator = new Authenticator(this);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return authenticator.getIBinder();
        }
    }

    public static class SyncService extends Service {
        private static SyncAdapter adapter;

        @Override
        public void onCreate() {
            synchronized (SyncService.class) {
                if (adapter == null) adapter = new SyncAdapter(getApplicationContext());
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return adapter.getSyncAdapterBinder();
        }
    }

    private static final class SyncAdapter extends AbstractThreadedSyncAdapter {
        SyncAdapter(Context context) {
            super(context, true);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                                  ContentProviderClient provider, SyncResult syncResult) {
            // カレンダーアプリの「今すぐ同期」から呼ばれることがある。手元のキャッシュで書き直すだけにする。
            DeviceCalendarSync.syncFromCacheAsync(getContext());
        }
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        Authenticator(Context context) {
            super(context);
        }

        // 設定アプリの「アカウントを追加」からは作らせない (アプリの設定で ON にしたときだけ作る)。
        @Override public Bundle addAccount(AccountAuthenticatorResponse r, String accountType, String authTokenType,
                                           String[] requiredFeatures, Bundle options) {
            Bundle b = new Bundle();
            b.putInt(android.accounts.AccountManager.KEY_ERROR_CODE, android.accounts.AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
            b.putString(android.accounts.AccountManager.KEY_ERROR_MESSAGE, "アプリの設定から有効にしてください");
            return b;
        }
        @Override public Bundle editProperties(AccountAuthenticatorResponse r, String accountType) { throw new UnsupportedOperationException(); }
        @Override public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account account, Bundle options) { return null; }
        @Override public Bundle getAuthToken(AccountAuthenticatorResponse r, Account account, String authTokenType, Bundle options) throws NetworkErrorException { throw new UnsupportedOperationException(); }
        @Override public String getAuthTokenLabel(String authTokenType) { throw new UnsupportedOperationException(); }
        @Override public Bundle updateCredentials(AccountAuthenticatorResponse r, Account account, String authTokenType, Bundle options) { throw new UnsupportedOperationException(); }
        @Override public Bundle hasFeatures(AccountAuthenticatorResponse r, Account account, String[] features) { throw new UnsupportedOperationException(); }
    }
}
