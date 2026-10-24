package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * IdP に渡した (渡す予定の) TOTP の窓。同じコードを 2 回送らないために使う (iOS の TOTPStepLedger と同じ)。
 *
 * アプリのログイン (CampusSquare と Moodle が同じ窓に続く) と、PC への中継 (OtpRelayResponder) が同じ鍵で
 * コードを作る。使用済みのコードは IdP に弾かれうる (弾かれると鍵を捨てることがある) ので、どちらも
 * この台帳で窓を使用済みにしてから使い、使用済みなら次の窓を待つ。SECIOSS が実際に弾くかは未確認。
 *
 * 中継も同じプロセスで動くが、プロセスが作り直されても覚えているよう、最後の窓の番号を prefs に置く
 * (番号だけで、鍵もコードも置かない)。
 */
public final class TotpStepLedger {
    /** 最後に使った窓の置き場所。テストではメモリに置く。 */
    public interface Store {
        long load();
        void save(long step);
    }

    private static TotpStepLedger shared;

    private final Store store;
    private long last = -1;

    public TotpStepLedger(@NonNull Store store) {
        this.store = store;
    }

    /** メモリにだけ持つ (テスト用)。 */
    public static TotpStepLedger inMemory() {
        long[] cell = {-1};
        return new TotpStepLedger(new Store() {
            @Override public long load() { return cell[0]; }
            @Override public void save(long step) { cell[0] = step; }
        });
    }

    /**
     * アプリ全体で 1 つ。PreferenceManager がまだ無い (JVM のテスト) ときは、このプロセスの中だけで持つ。
     */
    @NonNull
    public static synchronized TotpStepLedger shared() {
        if (shared == null) {
            shared = new TotpStepLedger(new Store() {
                private long memory = -1;

                @Override public long load() {
                    try {
                        return PreferenceManager.getInstance().getTotpLastUsedStep();
                    } catch (IllegalStateException e) {
                        return memory;
                    }
                }

                @Override public void save(long step) {
                    memory = step;
                    try {
                        PreferenceManager.getInstance().setTotpLastUsedStep(step);
                    } catch (IllegalStateException ignored) {
                        // テストなど、prefs が無い。メモリだけで持つ。
                    }
                }
            });
        }
        return shared;
    }

    /** まだ使っていない (直近に使ったものより新しい) 窓なら使用済みにして true。 */
    public synchronized boolean claim(long step) {
        long used = Math.max(last, store.load());
        if (step <= used) return false;
        last = step;
        store.save(step);
        return true;
    }
}
