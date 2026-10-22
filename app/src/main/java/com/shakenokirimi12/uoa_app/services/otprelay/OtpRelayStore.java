package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 連携一覧と直近の結果。保存先は PreferenceManager の暗号化側 (K を含むため)。
 * 通知の受信 (Worker) と設定画面の両方から触るので、読み書きはこのクラスで直列にする。
 */
public final class OtpRelayStore {
    private OtpRelayStore() {}

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();

    /** 直近の自動応答。iOS の OTPRelayLastResult と同じ項目。 */
    public static final class LastResult {
        public long at;
        public boolean succeeded;
        public String summary;
        @Nullable public String detail;
    }

    @NonNull
    public static List<OtpRelayPairing> all() {
        synchronized (LOCK) {
            String json = PreferenceManager.getInstance().getOtpRelayPairingsJson();
            if (json == null || json.isEmpty()) return new ArrayList<>();
            try {
                List<OtpRelayPairing> list = GSON.fromJson(json, new TypeToken<List<OtpRelayPairing>>() {}.getType());
                return list != null ? list : new ArrayList<>();
            } catch (RuntimeException e) {
                return new ArrayList<>();
            }
        }
    }

    @Nullable
    public static OtpRelayPairing find(@Nullable String pairingId) {
        if (pairingId == null) return null;
        for (OtpRelayPairing p : all()) {
            if (pairingId.equals(p.pairingId)) return p;
        }
        return null;
    }

    /** 同じ pairingId があれば置き換える。保存できたかを返す (暗号化ストアが無い端末では false)。 */
    public static boolean add(@NonNull OtpRelayPairing pairing) {
        synchronized (LOCK) {
            if (!PreferenceManager.getInstance().isSecureStorageAvailable()) return false;
            List<OtpRelayPairing> list = all();
            // 同じ QR を読み直したときは、確認ダイアログで得た同意ごと新しいものに置き換える。
            list.removeIf(p -> pairing.pairingId.equals(p.pairingId));
            list.add(pairing);
            return save(list);
        }
    }

    public static void remove(@NonNull String pairingId) {
        synchronized (LOCK) {
            List<OtpRelayPairing> list = all();
            list.removeIf(p -> pairingId.equals(p.pairingId));
            save(list);
        }
    }

    /**
     * 同意を記録する前に作った連携で、利用者が「続ける」を押した。保存できたかを返す
     * (一覧に無い = 解除済みなら何もせず true)。
     */
    public static boolean recordConsent(@NonNull String pairingId, long consentAt) {
        synchronized (LOCK) {
            List<OtpRelayPairing> list = all();
            boolean found = false;
            for (OtpRelayPairing p : list) {
                if (pairingId.equals(p.pairingId)) {
                    p.mailConsentAt = consentAt;
                    found = true;
                }
            }
            return !found || save(list);
        }
    }

    /** 同意の記録が無い連携のうち最初のもの。無ければ null。 */
    @Nullable
    public static OtpRelayPairing firstWithoutConsent() {
        for (OtpRelayPairing p : all()) {
            if (!p.hasMailConsent()) return p;
        }
        return null;
    }

    /** ログアウトで中継サーバからの解除を待っている連携。 */
    @NonNull
    public static List<OtpRelayPairing> pendingUnpairs() {
        synchronized (LOCK) {
            return parse(PreferenceManager.getInstance().getOtpRelayPendingUnpairsJson());
        }
    }

    /**
     * 今の連携を全部「解除待ち」へ移す (ログアウト)。移せたら true。
     * 一覧からは消さない。一覧は呼び出し側が clearAll で消す。
     */
    public static boolean moveAllToPendingUnpair() {
        synchronized (LOCK) {
            List<OtpRelayPairing> pending = parse(PreferenceManager.getInstance().getOtpRelayPendingUnpairsJson());
            for (OtpRelayPairing p : all()) {
                pending.removeIf(q -> p.pairingId.equals(q.pairingId));
                pending.add(p);
            }
            return PreferenceManager.getInstance().commitOtpRelayPendingUnpairsJson(
                    pending.isEmpty() ? null : GSON.toJson(pending));
        }
    }

    public static void removePendingUnpair(@NonNull String pairingId) {
        synchronized (LOCK) {
            List<OtpRelayPairing> pending = parse(PreferenceManager.getInstance().getOtpRelayPendingUnpairsJson());
            pending.removeIf(p -> pairingId.equals(p.pairingId));
            PreferenceManager.getInstance().commitOtpRelayPendingUnpairsJson(
                    pending.isEmpty() ? null : GSON.toJson(pending));
        }
    }

    @NonNull
    private static List<OtpRelayPairing> parse(@Nullable String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<OtpRelayPairing> list = GSON.fromJson(json, new TypeToken<List<OtpRelayPairing>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (RuntimeException e) {
            // 壊れた記録は読めない。空として扱うが、黙って捨てたことは残す。
            android.util.Log.w("OtpRelayStore", "pending unpair list unreadable; treating as empty: " + e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    /** 書き込みまで確定させる (apply だと失敗が分からず、連携の巻き戻しが走らない)。 */
    private static boolean save(List<OtpRelayPairing> list) {
        return PreferenceManager.getInstance().commitOtpRelayPairingsJson(list.isEmpty() ? null : GSON.toJson(list));
    }

    /** 答えた依頼 ID (直近 20 件)。依頼は 2 分で期限が切れるので、これで足りる。 */
    private static final java.util.LinkedHashSet<String> HANDLED = new java.util.LinkedHashSet<>();

    /** 同じプロセスで処理を終えた依頼か。 */
    public static boolean wasHandled(@NonNull String requestId) {
        synchronized (HANDLED) {
            return HANDLED.contains(requestId);
        }
    }

    /** 答えた依頼として記録する (答える前に止められた処理は記録しない = 再実行で続きをやる)。 */
    public static void markHandled(@NonNull String requestId) {
        synchronized (HANDLED) {
            HANDLED.add(requestId);
            while (HANDLED.size() > 20) {
                HANDLED.remove(HANDLED.iterator().next());
            }
        }
    }

    @Nullable
    public static LastResult lastResult() {
        String json = PreferenceManager.getInstance().getOtpRelayLastResultJson();
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, LastResult.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void setLastResult(boolean succeeded, @NonNull String summary, @Nullable String detail) {
        LastResult r = new LastResult();
        r.at = System.currentTimeMillis();
        r.succeeded = succeeded;
        r.summary = summary;
        r.detail = detail;
        PreferenceManager.getInstance().setOtpRelayLastResultJson(GSON.toJson(r));
    }
}
