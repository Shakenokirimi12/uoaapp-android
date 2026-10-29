package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * IdP へのログイン (パスワードの POST から SAML の戻りまで) を、サービスをまたいで同時に 1 本にする
 * (SPEC.md「5. 前面ログイン」、decisions D-9)。
 *
 * SessionCoordinator の単一飛行はサービスごとなので、CampusSquare と Moodle のログインは同時に走りうる。
 * 両方が IdP に入ると OTP メールが 2 通届き、入力も 2 回求められる。ここで 2 本目を待たせると、2 本目は 1 本目が
 * 保存した IdP の SSO で入るので、多くは OTP が要らなくなる。前面・バックグラウンドの両方に効かせる。
 *
 * OTP の入力を取り消したら (noteCancelled)、そのとき待っていたログインは始めずに INTERACTIVE_LOGIN_REQUIRED で返す。
 * 取り消した直後に次のサービスが入力を求め直さないため (iOS の C-1 と同じ問題)。
 */
public final class IdpLoginGate {
    public interface Body<T> { T run() throws Exception; }

    interface Clock { long now(); }

    private static final IdpLoginGate SHARED = new IdpLoginGate(System::currentTimeMillis);

    @NonNull
    public static IdpLoginGate shared() { return SHARED; }

    /** 来た順に通す (後から来たバックグラウンドが前面を追い越さない)。 */
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicLong cancelGeneration = new AtomicLong();
    private final Clock clock;
    private volatile long lastCancelledAt;

    IdpLoginGate(@NonNull Clock clock) {
        this.clock = clock;
    }

    /** body を、ほかの IdP ログインが終わってから 1 本だけで走らせる。 */
    public <T> T run(@NonNull Body<T> body) throws Exception {
        long generation = cancelGeneration.get();
        lock.lockInterruptibly();
        try {
            // 待っている間に、先のログインで OTP の入力が取り消された。
            if (cancelGeneration.get() != generation) {
                throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, "otp prompt cancelled");
            }
            return body.run();
        } finally {
            lock.unlock();
        }
    }

    /** OTP の入力を取り消した (時間切れを含む)。 */
    public void noteCancelled() {
        lastCancelledAt = clock.now();
        cancelGeneration.incrementAndGet();
    }

    /** since (epoch ミリ秒) 以降に OTP の入力が取り消されたか。同期の続きのログインを始めるかの判断に使う。 */
    public boolean cancelledSince(long since) {
        long at = lastCancelledAt;
        return at != 0 && at >= since;
    }
}
