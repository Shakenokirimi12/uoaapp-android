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
            // 同じ QR を読み直したときは、利用者が選んだ自動/承認を引き継ぐ (既定の自動に戻さない)。
            for (OtpRelayPairing p : list) {
                if (pairing.pairingId.equals(p.pairingId)) pairing.autoApprove = p.autoApprove;
            }
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

    public static void setAutoApprove(@NonNull String pairingId, boolean autoApprove) {
        synchronized (LOCK) {
            List<OtpRelayPairing> list = all();
            for (OtpRelayPairing p : list) {
                if (pairingId.equals(p.pairingId)) p.autoApprove = autoApprove;
            }
            save(list);
        }
    }

    /** 書き込みまで確定させる (apply だと失敗が分からず、連携の巻き戻しが走らない)。 */
    private static boolean save(List<OtpRelayPairing> list) {
        return PreferenceManager.getInstance().commitOtpRelayPairingsJson(list.isEmpty() ? null : GSON.toJson(list));
    }

    /** 処理した依頼 ID (直近 20 件)。依頼は 2 分で期限が切れるので、これで足りる。 */
    private static final java.util.LinkedHashSet<String> HANDLED = new java.util.LinkedHashSet<>();

    /** 初めての依頼なら記録して true。同じプロセスで既に処理していれば false。 */
    public static boolean markHandled(@NonNull String requestId) {
        synchronized (HANDLED) {
            if (!HANDLED.add(requestId)) return false;
            while (HANDLED.size() > 20) {
                HANDLED.remove(HANDLED.iterator().next());
            }
            return true;
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
