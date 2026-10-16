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
            list.removeIf(p -> pairing.pairingId.equals(p.pairingId));
            list.add(pairing);
            save(list);
            return find(pairing.pairingId) != null;
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

    private static void save(List<OtpRelayPairing> list) {
        PreferenceManager.getInstance().setOtpRelayPairingsJson(list.isEmpty() ? null : GSON.toJson(list));
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
