package com.shakenokirimi12.uoa_app.services;

import com.shakenokirimi12.uoa_app.services.idp.LoginEvidence;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;

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
 *      ID / パスワードが誤りと分かっている資格情報では、どの文脈でも始めない (Unattended.credentialsInvalid)。
 *      バックグラウンド (AuthorizationContext.BACKGROUND) からの要求は、Unattended (BackgroundLoginPolicy)
 *      が許したときだけ新規ログインを始める (方式ごとの条件と間隔は BackgroundLoginPolicy)。始めないときは
 *      INTERACTIVE_LOGIN_REQUIRED を返して諦め、次にユーザーが画面を開いたときに任せる。
 *      直前の前面の障害系失敗から LOGIN_BACKOFF_MS 以内なら再試行しない (バックグラウンドの失敗は前面を止めない)
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

    /**
     * バックグラウンドで新規ログインを始めてよいか。login と同じ ID / パスワードで作る
     * (BackgroundLoginPolicy.forAccount)。既定 (NEVER) は決して始めない。
     */
    interface Unattended {
        /** この資格情報は誤りと分かっている。前面でもバックグラウンドでもログインを始めない。lock の外で呼ぶ。 */
        boolean credentialsInvalid();
        /** バックグラウンドで始めてよいか (間隔の制限は含まない)。lock の外で呼ぶ。 */
        boolean permits();
        /** 間隔の制限。始めてよければ試行として記録して true。ほかの条件を全部通ってから、lock の外で呼ぶ。 */
        boolean claimSlot();
        /** ログインが成功した (文脈を問わない)。passwordAccepted: IdP にパスワードを送って通った (LoginEvidence)。 */
        void succeeded(boolean passwordAccepted);
        /** ログインが失敗した (background: バックグラウンドのログインだったか)。 */
        void failed(Exception e, boolean background);

        Unattended NEVER = new Unattended() {
            @Override public boolean credentialsInvalid() { return false; }
            @Override public boolean permits() { return false; }
            @Override public boolean claimSlot() { return false; }
            @Override public void succeeded(boolean passwordAccepted) {}
            @Override public void failed(Exception e, boolean background) {}
        };
    }

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
    /** 飛行中のログインがバックグラウンドのものか。前面の呼び出しはその失敗を受け取らず、自分でログインし直す。 */
    private boolean inFlightBackground;
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
    S session(Object method, AuthorizationContext context, S candidate, Probe<S> probe, Login<S> login) throws Exception {
        return session(method, context, candidate, probe, login, Unattended.NEVER);
    }

    /** unattended: バックグラウンドの新規ログインの判定。login と同じ ID / パスワードで作ったもの。 */
    S session(Object method, AuthorizationContext context, S candidate, Probe<S> probe, Login<S> login,
              Unattended unattended) throws Exception {
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

        // バックグラウンドで新規ログインしてよいか。暗号化した設定を読むので lock の外で先に決める。
        // 間隔の枠 (claimSlot) も設定を読むので lock の外で取り、取ったあとで lock の中の状態を見直す。
        boolean background = !context.allowsNewLogin();
        boolean mayLoginUnattended = background && unattended.permits();
        boolean credentialsInvalid = unattended.credentialsInvalid();
        boolean slotClaimed = false;

        while (true) {
            FutureTask<S> task;
            boolean owner = false;
            boolean waitThenRetry = false;
            boolean needSlot = false;
            synchronized (lock) {
                // probe している間に別スレッドのログインが終わっていれば、それを使う。
                if (live != null && Objects.equals(liveMethod, method) && verifiedAt >= began) return live;
                if (inFlight != null) {
                    task = inFlight;
                    // 別方式のログインが飛行中なら、その結果 (Cookie ヘッダの形が違う) は使えない。
                    // 前面の呼び出しがバックグラウンドのログインに合流すると、その失敗 (メールの枠が空いていない、
                    // OTP メールが取れなかった等) を受け取り、自分で OTP を入力する機会が無い。
                    // どちらも終わるのを待ってから、自分の文脈でやり直す (待つのは lock の外)。
                    waitThenRetry = !Objects.equals(inFlightMethod, method) || (inFlightBackground && !background);
                } else {
                    task = null;
                    // バックグラウンドでは、Unattended が許したときだけ新規ログインを始める。ここが唯一の判断点で、
                    // 許さなければ login クロージャ自体を呼ばない (フラグを見落とす経路を作らないため)。
                    if (credentialsInvalid) throw new Exception(AuthErrors.INVALID_CREDENTIALS_MESSAGE);
                    if (background && !mayLoginUnattended) {
                        throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
                    }
                    // 記録するのは前面の失敗だけ (runLogin)。バックグラウンドの失敗で前面を待たせない。
                    if (lastFailure != null
                            && System.currentTimeMillis() - lastFailureAt < LOGIN_BACKOFF_MS) {
                        throw lastFailure;
                    }
                    if (background && !slotClaimed) {
                        // 間隔の枠は、ほかの条件を全部通ってから取る (取ったら試行として記録される)。
                        needSlot = true;
                    } else {
                        task = new FutureTask<>(login::login);
                        inFlight = task;
                        inFlightMethod = method;
                        inFlightBackground = background;
                        owner = true;
                    }
                }
            }
            if (needSlot) {
                if (!unattended.claimSlot()) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
                // 枠を取る間に別スレッドがログインを始めた・終えたなら、次の周でそれに合流する・それを使う。
                slotClaimed = true;
                continue;
            }
            if (waitThenRetry) {
                awaitSettled(task);
                // 待っている間に、先のログインが ID / パスワードの誤りを見つけたかもしれない (印は lock の外で読む)。
                credentialsInvalid = unattended.credentialsInvalid();
                continue;
            }
            if (!owner) return await(task);
            return runLogin(task, method, unattended, background);
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
        return forceLogin(method, login, Unattended.NEVER);
    }

    /**
     * 前面のログインなので、成功したら unattended に伝える (パスワードの確認と、止めていた再ログインの解除)。
     * 誤りと分かっている資格情報 (同じ値の入れ直し) では始めない。
     */
    S forceLogin(Object method, Login<S> login, Unattended unattended) throws Exception {
        if (unattended.credentialsInvalid()) throw new Exception(AuthErrors.INVALID_CREDENTIALS_MESSAGE);
        while (true) {
            FutureTask<S> mine = null;
            FutureTask<S> other = null;
            synchronized (lock) {
                live = null;
                if (inFlight == null) {
                    mine = new FutureTask<>(login::login);
                    inFlight = mine;
                    inFlightMethod = method;
                    inFlightBackground = false;
                } else {
                    other = inFlight;
                }
            }
            if (mine != null) return runLogin(mine, method, unattended, false);
            // 先行ログインの成否は関係ない。終わったので自分のログインを始める。
            awaitSettled(other);
        }
    }

    /**
     * task が終わり、持ち主がその後始末 (inFlight を空ける) を終えるまで待つ。結果は使わない。
     * 後始末の前にやり直すと、同じ task に合流し直して空回りする。
     */
    private void awaitSettled(FutureTask<S> task) throws InterruptedException {
        try {
            task.get();
        } catch (ExecutionException ignored) {
            // 結果は使わない
        }
        synchronized (lock) {
            while (inFlight == task) lock.wait();
        }
    }

    private S runLogin(FutureTask<S> task, Object method, Unattended unattended, boolean background) throws Exception {
        try {
            LoginEvidence.begin();
            boolean passwordAccepted;
            try {
                task.run();
            } finally {
                passwordAccepted = LoginEvidence.end();
            }
            S session;
            try {
                session = task.get();
            } catch (ExecutionException e) {
                Exception cause = unwrap(e);
                synchronized (lock) {
                    // ID/PW 誤りは誤りの印で止まるので、ここで抑える対象は障害系だけ。バックグラウンドの失敗は
                    // 止めた印 (BackgroundLoginPolicy) が受け持ち、前面のログインは待たせない。
                    if (!background && !AuthErrors.isInvalidCredentials(cause.getMessage())) {
                        lastFailure = cause;
                        lastFailureAt = System.currentTimeMillis();
                    }
                }
                try {
                    unattended.failed(cause, background);
                } catch (RuntimeException recordFailed) {
                    // 印を書けなくても (暗号化した設定が読めない等)、ログインの失敗はそのまま返す。
                    cause.addSuppressed(recordFailed);
                }
                throw cause;
            }
            synchronized (lock) {
                setLive(session, method);
            }
            unattended.succeeded(passwordAccepted);
            return session;
        } finally {
            // 後始末は必ずする。残すと、以後の呼び出しが終わったログインに合流し続ける。
            synchronized (lock) {
                if (inFlight == task) inFlight = null;
                lock.notifyAll();
            }
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
