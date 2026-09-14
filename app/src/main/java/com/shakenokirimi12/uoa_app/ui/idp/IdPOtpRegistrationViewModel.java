package com.shakenokirimi12.uoa_app.ui.idp;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;
import com.shakenokirimi12.uoa_app.services.idp.SeciossRegistrationSession;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OTP (ワンタイムパスワード) 新規登録フローの状態機械 (iOS の IdPOTPRegistrationFlow の移植)。
 * SeciossRegistrationSession の複数ステップ呼び出しを、間にユーザー操作 (方式選択・確認・コード入力) を
 * 挟めるように ViewModel として持たせる。一度きりの登録という性質上、書き込み直前に必ず確認ステージを
 * 挟む (フレッシュな未設定アカウントは登録後に「未設定状態」を再現できないため)。
 */
public final class IdPOtpRegistrationViewModel extends ViewModel {
    public enum Method { EMAIL, AUTHENTICATOR }

    public enum Stage {
        CHECKING, ALREADY_CONFIGURED, CHOOSING_METHOD, ENTERING_EMAIL_ADDRESS, CONFIRMING_REGISTRATION,
        WAITING_FOR_CODE, COMPLETING, SUCCESS, FAILURE
    }

    private static final String DOMAIN = "@u-aizu.ac.jp";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SeciossIdPClient client = new SeciossIdPClient();
    private final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.CHECKING);
    private final MutableLiveData<String> failureMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> autoFetchingEmailCode = new MutableLiveData<>(false);

    private Method method = Method.EMAIL;
    @Nullable private String existingStatusLabel;
    @Nullable private String otpauthUrl;
    @Nullable private String authenticatorSecretFallback;
    private String uid = "";
    private String pass = "";
    private boolean started = false;

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

    /** アカウントが OTP 未設定であることを確認する。既に設定済みなら ALREADY_CONFIGURED で止まる。 */
    public void startOnce(@NonNull String uid, @NonNull String pass) {
        if (started) return;
        started = true;
        this.uid = uid.trim();
        this.pass = pass.trim();
        stage.setValue(Stage.CHECKING);
        executor.execute(() -> {
            try {
                session = client.beginOtpRegistration(SeciossRegistrationSession.ENTRY_URL, this.uid, this.pass);
                stage.postValue(Stage.CHOOSING_METHOD);
            } catch (SeciossError e) {
                if (e.kind == SeciossError.Kind.OTP_ALREADY_CONFIGURED) {
                    stage.postValue(Stage.ALREADY_CONFIGURED);
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
        executor.execute(() -> {
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
                stage.postValue(Stage.CONFIRMING_REGISTRATION);
            } catch (Exception e) {
                fail(e);
            }
        });
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
        executor.execute(() -> {
            try {
                s.requestEmailCode(address);
                SeciossRegistrationSession.EmailChallenge challenge = s.fetchEmailConfirmationChallenge();
                emailChallenge = challenge;
                existingStatusLabel = challenge.existingStatusLabel;
                stage.postValue(Stage.CONFIRMING_REGISTRATION);
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
        if (method != Method.EMAIL) return;
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

    private void completeEmail(String code) {
        SeciossRegistrationSession s = session;
        SeciossRegistrationSession.EmailChallenge challenge = emailChallenge;
        if (s == null || challenge == null) return;
        stage.postValue(Stage.COMPLETING);
        try {
            s.completeEmailRegistration(challenge, code);
            stage.postValue(Stage.SUCCESS);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void completeAuthenticator(String code) {
        SeciossRegistrationSession s = session;
        SeciossRegistrationSession.AuthenticatorChallenge challenge = authChallenge;
        if (s == null || challenge == null) return;
        stage.postValue(Stage.COMPLETING);
        try {
            s.completeAuthenticatorRegistration(challenge, code);
            stage.postValue(Stage.SUCCESS);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        String message = e instanceof SeciossError
                ? ((SeciossError) e).registrationMessage()
                : (e.getMessage() != null ? e.getMessage() : e.toString());
        failureMessage.postValue(message);
        stage.postValue(Stage.FAILURE);
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
