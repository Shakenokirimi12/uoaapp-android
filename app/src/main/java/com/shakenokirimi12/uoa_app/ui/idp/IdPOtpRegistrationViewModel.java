package com.shakenokirimi12.uoa_app.ui.idp;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;
import com.shakenokirimi12.uoa_app.services.idp.SeciossRegistrationSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTP (ワンタイムパスワード) 新規登録フローの状態機械 (iOS の IdPOTPRegistrationFlow の移植)。
 * SeciossRegistrationSession の複数ステップ呼び出しを、間にユーザー操作 (方式選択・確認・コード入力) を
 * 挟めるように ViewModel として持たせる。一度きりの登録という性質上、書き込み直前に必ず確認ステージを
 * 挟む (フレッシュな未設定アカウントは登録後に「未設定状態」を再現できないため)。
 *
 * 設定画面からの方式の切り替えにも使う (startOnce の switchingTo)。切り替えでは設定済みのアカウントでも
 * 止めずに登録まで進み、方式の選択は飛ばす。IdP の登録画面は設定済みのアカウントでも開け、
 * 「既に設定済みです: <方式>」と出したうえで登録を受け付ける (2026-09-07 の HAR)。登録で前の方式が
 * 置き換わるのか両方が残るのかは確かめられていないので、登録のあとに OTP 入力画面の otp_type を読み、
 * IdP がログインに使う方式が切り替え先になったことを確かめてから成功にする (OtpRegistrationCommit)。
 */
public final class IdPOtpRegistrationViewModel extends AndroidViewModel {
    public enum Method {
        EMAIL(OtpMethod.MAIL), AUTHENTICATOR(OtpMethod.AUTHENTICATOR);

        final OtpMethod otpMethod;

        Method(OtpMethod otpMethod) { this.otpMethod = otpMethod; }
    }

    /**
     * 登録のあとの確かめ (otp_type の読み取り) の上限。この間は画面を閉じられない (Stage.COMPLETING) ので
     * 打ち切る。通常経路の HTTP には短いタイムアウトを設けていない。
     */
    private static final long OTP_TYPE_CHECK_TIMEOUT_SECONDS = 20;

    public enum Stage {
        CHECKING, ALREADY_CONFIGURED, CHOOSING_METHOD, ENTERING_EMAIL_ADDRESS, CONFIRMING_REGISTRATION,
        WAITING_FOR_CODE, COMPLETING, SUCCESS, FAILURE,
        /** 登録の POST を送る前に画面を離れた (cancelBeforePost)。アカウントは何も変わっていない。 */
        CANCELLED
    }

    private static final String DOMAIN = "@u-aizu.ac.jp";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** cancelBeforePost のあと。書くのはメインスレッドだけ。executor からはトンネルを閉じる判断に読む。 */
    private volatile boolean cancelled = false;
    /** 通常経路 (学外)。OTP の設定状況の判定にだけ使う。 */
    private final SeciossIdPClient directClient = new SeciossIdPClient();
    /** startOnce でトンネルを張ってから作る。登録の HTTP は全部トンネル経由。 */
    private SeciossIdPClient client = directClient;
    /** executor スレッドで開き、onCleared (メインスレッド) で閉じるので volatile。 */
    @Nullable private volatile com.shakenokirimi12.uoa_app.services.idp.CampusSshTunnel tunnel;
    private final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.CHECKING);
    private final MutableLiveData<String> failureMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> autoFetchingEmailCode = new MutableLiveData<>(false);

    private Method method = Method.EMAIL;
    @Nullable private String existingStatusLabel;
    @Nullable private String otpauthUrl;
    @Nullable private String authenticatorSecretFallback;
    private String uid = "";
    private String pass = "";
    /** 確認コードの送り先がこの人の大学のメール (uid@u-aizu.ac.jp) か。違えば受信箱を読みに行っても見つからない。 */
    private boolean codeSentToOwnMailbox = true;
    private boolean started = false;
    /** startOnce で作る。POST の可否と、登録が済んだときの受け取り方の書き換えを受け持つ。 */
    @Nullable private volatile OtpRegistrationCommit commit;

    @Nullable private SeciossRegistrationSession session;
    @Nullable private SeciossRegistrationSession.EmailChallenge emailChallenge;
    @Nullable private SeciossRegistrationSession.AuthenticatorChallenge authChallenge;

    @NonNull public LiveData<Stage> stage() { return stage; }
    @NonNull public LiveData<String> failureMessage() { return failureMessage; }
    @NonNull public LiveData<Boolean> autoFetchingEmailCode() { return autoFetchingEmailCode; }
    @NonNull public Method method() { return method; }
    @Nullable public String existingStatusLabel() { return existingStatusLabel; }
    @Nullable public String otpauthUrl() { return otpauthUrl; }
    /** 外部の認証アプリが 1 つも無い場合の手動追加用フォールバック表示にだけ使う。 */
    @Nullable public String authenticatorSecretFallback() { return authenticatorSecretFallback; }
    @NonNull public String defaultEmailAddress() { return uid + DOMAIN; }
    /** 設定画面からの切り替えなら切り替え先。新規登録なら null。 */
    @Nullable public OtpMethod switchingTo() {
        OtpRegistrationCommit c = commit;
        return c != null ? c.switchingTo() : null;
    }

    public IdPOtpRegistrationViewModel(@NonNull Application application) {
        super(application);
    }

    /**
     * アカウントが OTP 未設定であることを確認する。既に設定済みなら ALREADY_CONFIGURED で止まる。
     * switchingTo を渡すと、設定済みのアカウントの方式をその方式に切り替える (方式の選択は出さない)。
     */
    public void startOnce(@NonNull String uid, @NonNull String pass, @Nullable OtpMethod switchingTo) {
        if (started) return;
        started = true;
        this.uid = uid.trim();
        this.pass = pass.trim();
        // 受け取り方を書き換えるのは、IdP への登録が済んだと分かったときだけ (OtpRegistrationCommit)。
        commit = new OtpRegistrationCommit(switchingTo,
                registered -> PreferenceManager.getInstance(getApplication()).applyRegisteredOtpMethod(registered),
                this::readOtpTypeWithinLimit);
        stage.setValue(Stage.CHECKING);
        executor.execute(() -> {
            try {
                // 手順は iOS 側 idp_verification/test_idp_full_auto.swift (2026-09-19 実機トレースで確認) と同じ:
                // 1. 通常経路でパスワードログインし、OTP の設定状況を判定する (学外だと未設定なら auth_err_020)。
                // 2. 未設定なら大学の SSH ゲートウェイ (AINS ID/PW 共通) へトンネルを張り、それ経由で
                //    もう一度パスワードログインする。学内扱いなので OTP を挟まずポータルに着く。その jar を
                //    登録セッションに引き継ぐ (登録画面はログインセッションが無いと tenantlogin に戻される)。
                // 通常経路でポータルまで通った (= 既に学内) 場合も、設定済みかどうかはポータルからは分からない
                // ので同じくトンネル経由で進み、設定画面の「既に設定済みです」表示で判断する。
                SeciossIdPClient.OtpStatus status =
                        directClient.checkOtpStatus(SeciossRegistrationSession.ENTRY_URL, this.uid, this.pass);
                // 切り替えは設定済みのアカウントが相手なので、ここでは止めない。
                if (status == SeciossIdPClient.OtpStatus.CONFIGURED && switchingTo == null) {
                    post(Stage.ALREADY_CONFIGURED);
                    return;
                }
                tunnel = com.shakenokirimi12.uoa_app.services.idp.CampusSshTunnel.open(this.uid, this.pass);
                // 接続を待つ間に画面を離れていたら、開いたトンネルをここで閉じて終える
                // (cancelBeforePost の時点ではまだ開いていなかった)。
                if (cancelled) {
                    closeTunnel();
                    return;
                }
                client = new SeciossIdPClient(tunnel);
                SeciossRegistrationSession s =
                        client.beginOtpRegistrationOnCampus(SeciossRegistrationSession.ENTRY_URL, this.uid, this.pass);
                session = s;
                if (switchingTo == null) {
                    post(Stage.CHOOSING_METHOD);
                } else if (switchingTo == OtpMethod.MAIL) {
                    // 切り替え先は設定画面で選んである。方式の選択を飛ばしてその方式の登録に進む。
                    method = Method.EMAIL;
                    post(Stage.ENTERING_EMAIL_ADDRESS);
                } else {
                    method = Method.AUTHENTICATOR;
                    loadAuthenticatorChallenge(s);
                }
            } catch (com.jcraft.jsch.JSchException e) {
                String gateway = com.shakenokirimi12.uoa_app.services.idp.CampusSshTunnel.GATEWAY_HOST;
                String msg = e.getMessage() != null && e.getMessage().contains("Auth")
                        ? "学内ゲートウェイ (" + gateway + ") への SSH 認証に失敗しました。AINS のパスワードが正しいか確認してください。"
                        : "学内ゲートウェイ (" + gateway + ") へ接続できませんでした: " + e.getMessage();
                postFailure(msg);
            } catch (SeciossError e) {
                if (e.kind == SeciossError.Kind.OTP_ALREADY_CONFIGURED && switchingTo != null) {
                    // 学内経路のはずなのに OTP を求められた = トンネルが学内扱いになっていない。
                    closeTunnel();
                    postFailure(string(R.string.otp_switch_not_on_campus));
                } else if (e.kind == SeciossError.Kind.OTP_ALREADY_CONFIGURED) {
                    closeTunnel();
                    post(Stage.ALREADY_CONFIGURED);
                } else {
                    fail(e);
                }
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    public void chooseEmail() {
        method = Method.EMAIL;
        stage.setValue(Stage.ENTERING_EMAIL_ADDRESS);
    }

    /**
     * QR ページ (実体は JS 内の平文 otpauth URL) を取得し、外部アプリへ渡す準備をする。
     * この時点ではまだ何もアカウントに書き込まない (確認ステージで止める)。
     */
    public void chooseAuthenticator() {
        method = Method.AUTHENTICATOR;
        SeciossRegistrationSession s = session;
        if (s == null) return;
        executor.execute(() -> loadAuthenticatorChallenge(s));
    }

    /** executor の上で呼ぶ。 */
    private void loadAuthenticatorChallenge(@NonNull SeciossRegistrationSession s) {
        try {
            SeciossRegistrationSession.AuthenticatorChallenge challenge = s.beginAuthenticatorRegistration();
            authChallenge = challenge;
            otpauthUrl = challenge.otpauthUrl;
            try {
                authenticatorSecretFallback = Uri.parse(challenge.otpauthUrl).getQueryParameter("secret");
            } catch (UnsupportedOperationException e) {
                authenticatorSecretFallback = null;
            }
            existingStatusLabel = challenge.existingStatusLabel;
            post(Stage.CONFIRMING_REGISTRATION);
        } catch (Exception e) {
            fail(e);
        }
    }

    /**
     * メール方式で送信先を確定し、確認コードの送信を依頼する。この時点でメールは実際に送信される
     * (通知メール自体は取り消せないが、アカウント設定はまだ変更されていない)。
     */
    public void confirmEmailAddressAndProceed(@NonNull String emailInput) {
        SeciossRegistrationSession s = session;
        if (s == null) return;
        String trimmed = emailInput.trim();
        String address = trimmed.isEmpty() ? defaultEmailAddress() : trimmed;
        codeSentToOwnMailbox = address.equalsIgnoreCase(defaultEmailAddress());
        executor.execute(() -> {
            try {
                s.requestEmailCode(address);
                SeciossRegistrationSession.EmailChallenge challenge = s.fetchEmailConfirmationChallenge();
                emailChallenge = challenge;
                existingStatusLabel = challenge.existingStatusLabel;
                post(Stage.CONFIRMING_REGISTRATION);
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    /**
     * 確認ステージで「登録を続ける」を押した後。ここから先が実際にアカウントを書き換える唯一の POST へ
     * 向かう (メールはコード自動取得後、Authenticator は手入力後)。
     */
    public void proceedAfterConfirmation() {
        stage.setValue(Stage.WAITING_FOR_CODE);
        // メールで受け取る方式なら、届いたコードはいつもアプリが読み取る (iOS の IdPOTPRegistrationFlow と同じ)。
        // 取れなければ手入力に任せる。別のアドレスに送ったときは、大学の受信箱を読みに行かない。
        if (method != Method.EMAIL || !codeSentToOwnMailbox) return;
        autoFetchingEmailCode.setValue(true);
        executor.execute(() -> {
            try {
                String code = new ImapOtpFetcher().fetchOtpCode(uid, pass, 30);
                autoFetchingEmailCode.postValue(false);
                completeEmail(code);
            } catch (Exception e) {
                // 自動取得できなくてもメール自体は届いているはずなので、手入力に切り替える。
                autoFetchingEmailCode.postValue(false);
            }
        });
    }

    public void submitCode(@NonNull String input) {
        String code = input.trim();
        if (code.isEmpty()) return;
        executor.execute(() -> {
            if (method == Method.EMAIL) completeEmail(code);
            else completeAuthenticator(code);
        });
    }

    // 登録の POST は、画面が閉じられていたら送らない (OtpRegistrationCommit.tryBeginPost)。送ったあとは
    // 画面を閉じられないようにしてあり (Stage.COMPLETING)、それでも閉じられたら処理を止めずに最後まで走らせる
    // (onCleared)。どちらも、IdP だけが変わって受け取り方が取り残されるのを防ぐため。

    private void completeEmail(String code) {
        SeciossRegistrationSession s = session;
        SeciossRegistrationSession.EmailChallenge challenge = emailChallenge;
        OtpRegistrationCommit c = commit;
        if (s == null || challenge == null || c == null || !beginPostOnMain(c)) return;
        try {
            s.completeEmailRegistration(challenge, code);
            closeTunnel();
            finishRegistration(c, Method.EMAIL);
        } catch (Exception e) {
            failRegistration(c, Method.EMAIL, e);
        }
    }

    private void completeAuthenticator(String code) {
        SeciossRegistrationSession s = session;
        SeciossRegistrationSession.AuthenticatorChallenge challenge = authChallenge;
        OtpRegistrationCommit c = commit;
        if (s == null || challenge == null || c == null || !beginPostOnMain(c)) return;
        try {
            s.completeAuthenticatorRegistration(challenge, code);
            closeTunnel();
            finishRegistration(c, Method.AUTHENTICATOR);
        } catch (Exception e) {
            failRegistration(c, Method.AUTHENTICATOR, e);
        }
    }

    /** IdP が「設定が完了しました」を返したあと。 */
    private void finishRegistration(@NonNull OtpRegistrationCommit c, @NonNull Method registered) {
        if (c.afterCompletion(registered.otpMethod) == OtpRegistrationCommit.Result.SUCCEEDED) {
            post(Stage.SUCCESS);
        } else {
            postFailure(string(R.string.otp_switch_not_switched));
        }
    }

    /** 登録の POST が完了の表示を返さなかったとき。 */
    private void failRegistration(@NonNull OtpRegistrationCommit c, @NonNull Method registered, @NonNull Exception e) {
        closeTunnel();
        if (c.afterError(registered.otpMethod) == OtpRegistrationCommit.Result.SUCCEEDED) {
            android.util.Log.i("IdPOtpRegistration", "registration response unrecognized, but otp_type shows the new method");
            post(Stage.SUCCESS);
        } else {
            fail(e);
        }
    }

    /**
     * 通常経路でパスワードだけ入り、OTP 入力画面の otp_type を読む。メールの送信は頼まない。
     * 学内にいてポータルまで通った・通信に失敗した・値が無い・20 秒で終わらなかったときは null。
     */
    @Nullable
    private String readOtpTypeWithinLimit() {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<String> read = one.submit(() ->
                    directClient.readOtpType(SeciossRegistrationSession.ENTRY_URL, uid, pass));
            String otpType = read.get(OTP_TYPE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            android.util.Log.i("IdPOtpRegistration", "otp_type after registration: " + (otpType != null ? otpType : "unreadable"));
            return otpType;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            android.util.Log.i("IdPOtpRegistration", "otp_type unreadable after registration: " + e);
            return null;
        } finally {
            one.shutdownNow();
        }
    }

    @NonNull
    private String string(@StringRes int res) {
        return getApplication().getString(res);
    }

    /**
     * executor から段を進める。中断 (cancelBeforePost) のあとは何も変えない。メインスレッドで判定するので、
     * 中断より前に積まれた更新が中断の表示を上書きすることもない。
     */
    private void post(@NonNull Stage next) {
        main.post(() -> {
            if (!cancelled) stage.setValue(next);
        });
    }

    private void postFailure(@NonNull String message) {
        main.post(() -> {
            if (cancelled) return;
            failureMessage.setValue(message);
            stage.setValue(Stage.FAILURE);
        });
    }

    /**
     * executor から呼ぶ。登録の POST を送ってよいかをメインスレッドで決め、よければ同じ処理の中で
     * COMPLETING にする。observer が同期で呼ばれて戻る操作を塞ぐので (IdPOtpRegistrationFragment.render)、
     * 「POST を送ると決めたのに、まだ画面を閉じられる」間が無い。画面を閉じる処理 (onDestroyView /
     * onCleared) もメインスレッドなので、どちらが先かがはっきりする。
     */
    private boolean beginPostOnMain(@NonNull OtpRegistrationCommit c) {
        CountDownLatch decided = new CountDownLatch(1);
        AtomicBoolean allowed = new AtomicBoolean(false);
        main.post(() -> {
            if (!cancelled && c.tryBeginPost()) {
                allowed.set(true);
                stage.setValue(Stage.COMPLETING);
            }
            decided.countDown();
        });
        try {
            decided.await();
        } catch (InterruptedException e) {
            // onCleared の shutdownNow。それより前に許可されていれば shutdownNow は呼ばれないので、ここは不許可。
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed.get();
    }

    /**
     * 画面を離れた (戻る・下タブで別の画面へ)。POST をまだ送っていなければ、以後は送らない。
     * 画面が見えている間にしか登録しないため。認証アプリやメールのアプリへ移るだけ (画面は残る) では呼ばない。
     * メインスレッドで呼ぶ。
     */
    public void cancelBeforePost() {
        if (cancelled) return;
        OtpRegistrationCommit c = commit;
        if (c != null && c.dismiss()) return;
        Stage current = stage.getValue();
        if (current == Stage.SUCCESS || current == Stage.FAILURE || current == Stage.ALREADY_CONFIGURED) return;
        cancelled = true;
        stage.setValue(Stage.CANCELLED);
        autoFetchingEmailCode.setValue(false);
        executor.shutdownNow();
        // SSH の切断は通信するので、メインスレッドでは行わない。
        new Thread(this::closeTunnel, "otp-registration-close-tunnel").start();
    }

    /** 失敗は終点 (この画面でやり直さない) なので、SSH セッションもここで閉じる。 */
    private void fail(Exception e) {
        closeTunnel();
        String message = e instanceof SeciossError
                ? ((SeciossError) e).registrationMessage()
                : (e.getMessage() != null ? e.getMessage() : e.toString());
        postFailure(message);
    }

    /** 登録が終わったら SSH セッションを持ったままにしない。 */
    private synchronized void closeTunnel() {
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
    }

    @Override
    protected void onCleared() {
        OtpRegistrationCommit c = commit;
        if (c != null && c.dismiss()) {
            // 登録の POST をもう送っている。止めると成功を受け取れず、IdP だけが変わる。最後まで走らせる
            // (受け取り方の書き換えも、トンネルを閉じるのもその中で行う)。
            executor.shutdown();
            return;
        }
        executor.shutdownNow();
        closeTunnel();
    }
}
