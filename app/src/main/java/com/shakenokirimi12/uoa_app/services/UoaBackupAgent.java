package com.shakenokirimi12.uoa_app.services;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * OS の自動バックアップ (Google ドライブ) と端末間転送の復元直後に、端末固有の値を消す。
 *
 * 平文の prefs (uoa_app_prefs.xml) は設定と一緒に丸ごとバックアップされ、そこに device_id と fcm_token も
 * 入っている。そのまま新しい端末へ戻ると、2 台が同じ device_id でサーバーに登録してプッシュと課題リマインダーの
 * 予約を取り合う。バックアップ規則 (xml/backup_rules, xml/data_extraction_rules) はファイル単位でしか除外
 * できないので、ここで復元後に消す。復元はアプリの初回起動より前なので、消しておけば新しい端末は自分の id を作る。
 *
 * Firebase 側の保存は対象にしない。2026-10-16 にエミュレータで見たところ、Installations の ID は
 * no_backup/ (バックアップされない) にあり、FCM はトークンのキャッシュとは別に復元検知用の目印
 * (no_backup/com.google.android.gms.appid-no-backup) を置いている。こちらが写して持つ fcm_token は
 * それと無関係に復元されるので、ここで消す。
 *
 * fullBackupOnly="true" なので、バックアップの中身は自動バックアップのまま (onBackup / onRestore は使われない)。
 * 復元の間はアプリが制限モードで動き UoaApplication が初期化されないので、PreferenceManager は使わず
 * prefs を直接開く。
 */
public class UoaBackupAgent extends BackupAgent {
    private static final String TAG = "UoaBackupAgent";

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState) {
        // fullBackupOnly のため呼ばれない (キー値バックアップは使っていない)。
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
        // 同上。
    }

    @Override
    public void onRestoreFinished() {
        super.onRestoreFinished();
        SharedPreferences prefs = getSharedPreferences(PreferenceManager.PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        int removed = removeDeviceLocal(prefs.getAll().keySet(), editor);
        // 復元の直後にプロセスが終わるので、非同期の apply ではなく書き込みを待つ。
        if (!editor.commit()) Log.w(TAG, "could not drop device-local keys after restore");
        Log.i(TAG, "restore finished; dropped " + removed + " device-local value(s)");
    }

    /** present のうち端末固有のキーを editor で消す。消した数を返す。 */
    static int removeDeviceLocal(@NonNull java.util.Set<String> present, @NonNull SharedPreferences.Editor editor) {
        int removed = 0;
        for (String key : PreferenceManager.DEVICE_LOCAL_KEYS) {
            if (present.contains(key)) {
                editor.remove(key);
                removed++;
            }
        }
        return removed;
    }
}
