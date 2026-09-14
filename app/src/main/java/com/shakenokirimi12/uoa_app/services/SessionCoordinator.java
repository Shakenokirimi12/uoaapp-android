package com.shakenokirimi12.uoa_app.services;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 取得のたびにログインし直すのをやめるための、プロセス内で共有するセッション状態
 * (iOS の CampusSquareService.SessionCoordinator を Java に移したもの)。
 * 旧方式でも大学側に毎回ログインが記録され、IdP 方式では同期のたびに OTP メールまで飛ぶので、
 * ログインするのは「セッションが本当に切れている」と確認できた時だけにする:
 *   1. 直近 TRUST_WINDOW_MS 以内に検証済み → そのまま使う (リクエスト 0)
 *   2. それ以外は probe で 1 リクエストだけ確認し、認証済みなら検証時刻だけ更新
 *   3. 未認証なら単一飛行でログイン。同時に来た呼び出しは同じログインの結果を待つ。
 *      バックグラウンド (userInitiated=false) からの要求は、直前の障害系失敗から
 *      LOGIN_BACKOFF_MS 以内なら再試行せず同じエラーを返す (ユーザー操作は常に試す)
 *
 * 各画面と SyncWorker はそれぞれ別のサービスインスタンス (= 別の単一スレッド executor) から
 * 同時にここへ来るため、素の static フィールドでは単一飛行が破れて二重ログイン (= 二重 OTP メール)
 * になる。状態の読み書きはすべて lock の下で行い、ネットワーク I/O (probe / login) は lock の外で行う。
 *
 * @param <S> セッションの表現 (CampusSquare は Cookie ヘッダ文字列、Moodle は sesskey)
 */
final class SessionCoordinator<S> {
    /** 生存確認。生きていればそのセッション (確認の副産物で更新された値でもよい) を、死んでいれば null を返す。 */
    interface Probe<S> { S refresh(S session); }
    interface Login<S> { S login() throws Exception; }

    static final long TRUST_WINDOW_MS = 10 * 60 * 1000L;
    static final long LOGIN_BACKOFF_MS = 60 * 1000L;

    private final Object lock = new Object();
    private S live;
    private Object liveMethod;
    private long verifiedAt;
    /** setLive のたびに進む。probe 中に別スレッドが同じ値で再検証しただけのケースを、参照比較では区別できないため。 */
    private long generation;
    private FutureTask<S> inFlight;
    private Object inFlightMethod;
    private Exception lastFailure;
    private long lastFailureAt;

    /**
     * 取得中に expired が未認証と判明したときに呼ぶ。次回は probe からやり直す。
     * 別スレッドが既に別のセッションへログインし直していたら、それは触らない。
     */
    void invalidate(S expired) {
        synchronized (lock) {
            if (Objects.equals(live, expired)) live = null;
        }
    }

    /** ログアウト時。失敗の記録も含めて全部捨てる。 */
    void reset() {
        synchronized (lock) {
            live = null;
            lastFailure = null;
            lastFailureAt = 0;
        }
    }

    /**
     * 認証済みセッションを返す。必要なときだけ probe / login を呼ぶ。
     *
     * @param method    ログイン方式。前回と違えば手持ちのセッションは信用しない
     * @param candidate live が無いときに、ログインの前に試す候補 (前回プロセスが永続化した cookie 等)。無ければ null
     */
    S session(Object method, boolean userInitiated, S candidate, Probe<S> probe, Login<S> login) throws Exception {
        S current;
        long currentGen;
        synchronized (lock) {
            if (live != null && Objects.equals(liveMethod, method)) {
                if (System.currentTimeMillis() - verifiedAt < TRUST_WINDOW_MS) return live;
                current = live;
            } else {
                current = null;
            }
            currentGen = generation;
        }
        long began = System.currentTimeMillis();

        if (current != null) {
            S refreshed = probe.refresh(current);
            synchronized (lock) {
                // probe 中に別スレッドが差し替え/再検証していたら、その結果には触らない
                // (こちらの probe だけ失敗していても、向こうが確認したセッションを消さない)。
                if (live == current && generation == currentGen) {
                    if (refreshed != null) {
                        setLive(refreshed, method);
                        return refreshed;
                    }
                    live = null;
                }
            }
            if (refreshed != null) return refreshed;
        }

        if (candidate != null) {
            S refreshed = probe.refresh(candidate);
            if (refreshed != null) {
                synchronized (lock) {
                    setLive(refreshed, method);
                }
                return refreshed;
            }
        }

        while (true) {
            FutureTask<S> task;
            boolean owner;
            boolean otherMethod = false;
            synchronized (lock) {
                // probe している間に別スレッドのログインが終わっていれば、それを使う。
                if (live != null && Objects.equals(liveMethod, method) && verifiedAt >= began) return live;
                if (inFlight != null) {
                    task = inFlight;
                    owner = false;
                    // 別方式のログインが飛行中なら、その結果 (Cookie ヘッダの形が違う) は使えない。
                    // 終わるのを待ってから自分の方式でやり直す (待つのは lock の外)。
                    otherMethod = !Objects.equals(inFlightMethod, method);
                } else {
                    if (!userInitiated && lastFailure != null
                            && System.currentTimeMillis() - lastFailureAt < LOGIN_BACKOFF_MS) {
                        throw lastFailure;
                    }
                    task = new FutureTask<>(login::login);
                    inFlight = task;
                    inFlightMethod = method;
                    owner = true;
                }
            }
            if (otherMethod) {
                awaitIgnoringResult(task);
                continue;
            }
            if (!owner) return await(task);
            return runLogin(task, method);
        }
    }

    /** lock の下で呼ぶ。生きたセッションがある間は「直前の失敗」の記録を持ち越さない。 */
    private void setLive(S session, Object method) {
        live = session;
        liveMethod = method;
        verifiedAt = System.currentTimeMillis();
        lastFailure = null;
        generation++;
    }

    /**
     * 手持ちのセッションを捨てて必ずログインし直す (資格情報の確認用: オンボーディング・設定変更)。
     * 別のログインが飛行中なら、その結果は使わずに終わるのを待ってから自分のログインを始める。
     * 入力し直した資格情報を検証しているのに、古い資格情報のログイン結果を返しては意味が無いため。
     */
    S forceLogin(Object method, Login<S> login) throws Exception {
        while (true) {
            FutureTask<S> mine = null;
            FutureTask<S> other = null;
            synchronized (lock) {
                live = null;
                if (inFlight == null) {
                    mine = new FutureTask<>(login::login);
                    inFlight = mine;
                    inFlightMethod = method;
                } else {
                    other = inFlight;
                }
            }
            if (mine != null) return runLogin(mine, method);
            // 先行ログインの成否は関係ない。終わったので自分のログインを始める。
            awaitIgnoringResult(other);
        }
    }

    private static <S> void awaitIgnoringResult(FutureTask<S> task) throws InterruptedException {
        try {
            task.get();
        } catch (ExecutionException ignored) {
            // 結果は使わない
        }
    }

    private S runLogin(FutureTask<S> task, Object method) throws Exception {
        task.run();
        try {
            S session = task.get();
            synchronized (lock) {
                setLive(session, method);
                if (inFlight == task) inFlight = null;
            }
            return session;
        } catch (ExecutionException e) {
            Exception cause = unwrap(e);
            synchronized (lock) {
                if (inFlight == task) inFlight = null;
                // ID/PW 誤りは呼び出し側が同期を止めるので、ここで抑える対象は障害系だけ。
                if (!AuthErrors.isInvalidCredentials(cause.getMessage())) {
                    lastFailure = cause;
                    lastFailureAt = System.currentTimeMillis();
                }
            }
            throw cause;
        }
    }

    private static <S> S await(FutureTask<S> task) throws Exception {
        try {
            return task.get();
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) return (Exception) cause;
        return new Exception(cause != null ? cause.getMessage() : e.getMessage(), cause);
    }
}
