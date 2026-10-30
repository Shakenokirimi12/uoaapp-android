package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.AppStrings;

/**
 * SeciossIdPClient / SeciossRegistrationSession が投げる失敗。iOS の SeciossError (enum) と
 * 1 対 1 に対応させ、呼び出し側が種別で分岐できるようにする。
 */
public final class SeciossError extends Exception {
    public enum Kind {
        /** 想定外の HTTP ステータス。status と step (どの段階か) を持つ。 */
        UNEXPECTED_STATUS,
        /** ログインフォーム等に必須フィールドが無い (画面の形が想定と違う)。 */
        MISSING_FIELD,
        OTP_REQUEST_FAILED,
        /** OTP を送ったが、IdP が OTP の入力画面へ戻した (コードの誤りか期限切れ。iOS の invalidOTP と同じ見分け方)。 */
        INVALID_OTP,
        /**
         * アカウントにワンタイムパスワードが未設定 (pub/error.cgi、2026-09-07 に実機確認済み)。
         * 学内ネットワークから事前設定するまで学外アクセスできないため、リトライしても無駄。
         */
        OTP_NOT_CONFIGURED,
        /** checkOtpStatus / beginOtpRegistrationOnCampus で、既に OTP が設定済み (ログイン時に OTP フォームが出る状態) だった。 */
        OTP_ALREADY_CONFIGURED,
        /** 想定していない画面に到達した (中継フォームでも OTP フォームでも成功マーカーでもない)。 */
        UNRECOGNIZED_STATE,
        /**
         * SLINK 側の sessid セッションが期限切れ (「タイムアウトしました」、2026-09-08 に実機で観測)。
         * IMAP 自動 OTP 取得 (最大 30 秒) を挟むと起きやすい。
         */
        SESSION_TIMED_OUT,
        /**
         * 人の操作が要るので、この文脈では新規ログインを進めない。バックグラウンドで新規ログインの条件
         * (BackgroundLoginPolicy) を満たさない、メール方式の 6 時間の枠が空いていない、前面で OTP の入力を
         * 取り消した、など。アプリ内の事情なので、バックグラウンドの「止めた印」は立てない。次に前面へ来たときに任せる。
         */
        INTERACTIVE_LOGIN_REQUIRED,
        /**
         * 認証アプリのコードを作れる未使用の窓が、待てる間に来なかった (同じ窓を中継や別のログインが使った)。
         * アプリ内の事情なので、バックグラウンドの「止めた印」は立てない (SPEC.md「6. バックグラウンドログイン」)。
         */
        TOTP_STEP_UNAVAILABLE,
        /** バックグラウンドのメール方式で、OTP メールを IMAP から取れなかった (手入力は待たない)。 */
        OTP_FETCH_FAILED,
        /**
         * ID/PW が誤っている。同じ資格情報でリトライし続けると大学側でアカウントがロックされうるため、
         * 他の失敗と必ず区別して呼び出し元に伝える。
         */
        INVALID_CREDENTIALS,
        /**
         * IdP 側でこのユーザー (またはテナント) に SP へのアクセス権が無い (pub/error.cgi?msg=auth_err_003
         * 「アクセスが許可されていません」、2026-09-14 に CampusSquare 経由で実測)。大学側が SP を開放する
         * までリトライしても無駄。detail にエラーコード (msg) を持つ。
         */
        ACCESS_DENIED,
        /**
         * SP がメンテナンス中 (HTTP 503 / 「メンテナンスモード」ページ、2026-09-15 に Moodle で実測)。
         * ログイン自体は成功していても中身は取れないので、資格情報エラーやマーカー不在と区別して伝える。
         * detail にページの可視テキストの抜粋を持つ。
         */
        MAINTENANCE
    }

    @NonNull public final Kind kind;
    /** UNEXPECTED_STATUS のときの HTTP ステータス。それ以外は 0。 */
    public final int status;
    /** MISSING_FIELD のフィールド名、UNEXPECTED_STATUS の段階名、UNRECOGNIZED_STATE の説明。無ければ null。 */
    @Nullable public final String detail;

    public SeciossError(@NonNull Kind kind) {
        this(kind, 0, null);
    }

    public SeciossError(@NonNull Kind kind, @Nullable String detail) {
        this(kind, 0, detail);
    }

    public SeciossError(@NonNull Kind kind, int status, @Nullable String detail) {
        super(kind.name() + (detail != null ? ": " + detail : "") + (status != 0 ? " (HTTP " + status + ")" : ""));
        this.kind = kind;
        this.status = status;
        this.detail = detail;
    }

    /**
     * 呼び出し元へ返す形 (文言はログイン画面向け)。原因としてこの SeciossError を残す。種類で判断するところ
     * (BackgroundLoginPolicy の停止の判定) が、文言ではなく種類を読めるように。
     */
    @NonNull
    public Exception asLoginException() {
        return new Exception(loginMessage(), this);
    }

    /** ログイン画面向けの文言 (iOS の IdPLoginFlow.describe と同じ)。 */
    @NonNull
    public String loginMessage() {
        switch (kind) {
            case MISSING_FIELD:
                // 大学のログイン画面の形が想定と違った。CampusSquare は 2026-09-15 から IdP 経由なので、
                // 以前の「まだ IdP 化されていない可能性」は当たらない。
                return AppStrings.get(R.string.secioss_error_login_missing_field, detail);
            case UNEXPECTED_STATUS:
                return AppStrings.get(R.string.secioss_error_unexpected_status, detail, status);
            case OTP_REQUEST_FAILED:
                return AppStrings.get(R.string.secioss_error_otp_request_failed);
            case INVALID_OTP:
                return AppStrings.get(R.string.secioss_error_invalid_otp);
            case OTP_NOT_CONFIGURED:
                return AppStrings.get(R.string.secioss_error_otp_not_configured);
            case OTP_ALREADY_CONFIGURED:
                return AppStrings.get(R.string.secioss_error_otp_already_configured);
            case UNRECOGNIZED_STATE:
                return AppStrings.get(R.string.secioss_error_login_unrecognized_state, detail);
            case SESSION_TIMED_OUT:
                return AppStrings.get(R.string.secioss_error_session_timed_out);
            case INTERACTIVE_LOGIN_REQUIRED:
                return AppStrings.get(R.string.secioss_error_interactive_login_required);
            case TOTP_STEP_UNAVAILABLE:
                return AppStrings.get(R.string.secioss_error_totp_step_unavailable);
            case OTP_FETCH_FAILED:
                return AppStrings.get(R.string.secioss_error_otp_fetch_failed);
            case MAINTENANCE:
                return AppStrings.get(R.string.secioss_error_maintenance, detail);
            case ACCESS_DENIED:
                if ("moodle_login_page".equals(detail)) {
                    return AppStrings.get(R.string.secioss_error_moodle_sso_not_ready);
                }
                if ("campussquare_sso_error".equals(detail)) {
                    return AppStrings.get(R.string.secioss_error_campussquare_sso_not_ready);
                }
                return AppStrings.get(R.string.secioss_error_access_denied, detail);
            case INVALID_CREDENTIALS:
            default:
                return AppStrings.get(R.string.secioss_error_invalid_credentials);
        }
    }

    /** OTP 登録画面向けの文言 (iOS の IdPOTPRegistrationFlow.describe と同じ)。 */
    @NonNull
    public String registrationMessage() {
        switch (kind) {
            case MISSING_FIELD:
                return AppStrings.get(R.string.secioss_error_registration_missing_field, detail);
            case UNEXPECTED_STATUS:
                return AppStrings.get(R.string.secioss_error_unexpected_status, detail, status);
            case OTP_REQUEST_FAILED:
                return AppStrings.get(R.string.secioss_error_registration_otp_request_failed);
            case INVALID_OTP:
                return AppStrings.get(R.string.secioss_error_registration_invalid_otp);
            case OTP_NOT_CONFIGURED:
                return AppStrings.get(R.string.secioss_error_registration_otp_not_configured);
            case OTP_ALREADY_CONFIGURED:
                return AppStrings.get(R.string.secioss_error_otp_already_configured);
            case UNRECOGNIZED_STATE:
                return AppStrings.get(R.string.secioss_error_registration_unrecognized_state, detail);
            case SESSION_TIMED_OUT:
                return AppStrings.get(R.string.secioss_error_session_timed_out);
            case INTERACTIVE_LOGIN_REQUIRED:
                return AppStrings.get(R.string.secioss_error_registration_interactive_login_required);
            case TOTP_STEP_UNAVAILABLE:
                return AppStrings.get(R.string.secioss_error_registration_totp_step_unavailable);
            case OTP_FETCH_FAILED:
                return AppStrings.get(R.string.secioss_error_registration_otp_fetch_failed);
            case MAINTENANCE:
                return AppStrings.get(R.string.secioss_error_maintenance, detail);
            case ACCESS_DENIED:
                return AppStrings.get(R.string.secioss_error_registration_access_denied, detail);
            case INVALID_CREDENTIALS:
            default:
                return AppStrings.get(R.string.secioss_error_invalid_credentials);
        }
    }
}
