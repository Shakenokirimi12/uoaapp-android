package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * SeciossIdPClient / SeciossRegistrationSession が投げる失敗。iOS の SeciossError (enum) と
 * 1 対 1 に対応させ、呼び出し側が種別で分岐できるようにする。
 */
public final class SeciossError extends Exception {
    public enum Kind {
        /** 想定外の HTTP ステータス。status と step (どの段階か) を持つ。 */
        UNEXPECTED_STATUS,
        /** ログインフォーム等に必須フィールドが無い (CampusSquare がまだ IdP に繋がっていない場合もここ)。 */
        MISSING_FIELD,
        OTP_REQUEST_FAILED,
        /**
         * アカウントにワンタイムパスワードが未設定 (pub/error.cgi、2026-09-07 に実機確認済み)。
         * 学内ネットワークから事前設定するまで学外アクセスできないため、リトライしても無駄。
         */
        OTP_NOT_CONFIGURED,
        /** beginOtpRegistration で、既に OTP が設定済み (ログイン時に OTP フォームが出る状態) だった。 */
        OTP_ALREADY_CONFIGURED,
        /** 想定していない画面に到達した (中継フォームでも OTP フォームでも成功マーカーでもない)。 */
        UNRECOGNIZED_STATE,
        /**
         * SLINK 側の sessid セッションが期限切れ (「タイムアウトしました」、2026-09-08 に実機で観測)。
         * IMAP 自動 OTP 取得 (最大 30 秒) を挟むと起きやすい。
         */
        SESSION_TIMED_OUT,
        /**
         * OTP が必要だが、ユーザーがメール自動取得 (IMAP) を許可していない。バックグラウンド同期は
         * OTP 入力を待ち受けられないため、ここで明示的に止めて設定画面からの手動ログインに誘導する。
         */
        INTERACTIVE_LOGIN_REQUIRED,
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
        ACCESS_DENIED
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

    /** ログイン画面向けの文言 (iOS の IdPLoginFlow.describe と同じ)。 */
    @NonNull
    public String loginMessage() {
        switch (kind) {
            case MISSING_FIELD:
                // CampusSquare がまだ IdP に繋がっていない場合、ここに来る想定。
                return "IdPのログイン画面が見つかりませんでした(" + detail + ")。まだCampusSquareがIdP化されていない可能性があります。";
            case UNEXPECTED_STATUS:
                return detail + "で想定外の応答(HTTP " + status + ")でした。";
            case OTP_REQUEST_FAILED:
                return "ワンタイムパスワードの送信に失敗しました。";
            case OTP_NOT_CONFIGURED:
                return "このアカウントはワンタイムパスワードが未設定です。学内ネットワークから事前に設定してください(学外からの設定はできません)。";
            case OTP_ALREADY_CONFIGURED:
                return "このアカウントは既にワンタイムパスワードが設定済みです。";
            case UNRECOGNIZED_STATE:
                return "想定外の画面に到達しました(" + detail + ")。画面の表示が変わった可能性があります。";
            case SESSION_TIMED_OUT:
                return "IdP側のセッションがタイムアウトしました。もう一度ログインからやり直してください。";
            case INTERACTIVE_LOGIN_REQUIRED:
                return "ワンタイムパスワードの入力が必要です。";
            case ACCESS_DENIED:
                if ("campussquare_sso_error".equals(detail)) {
                    return "CampusSquare 側でこのアカウントのシングルサインオンがまだ有効になっていません。大学側の移行作業が完了するまで利用できません。";
                }
                return "大学の認証システム(IdP)がこのサービスへのアクセスを許可していません(" + detail + ")。大学側の設定が完了するまで利用できません。";
            case INVALID_CREDENTIALS:
            default:
                return "ユーザー名またはパスワードが正しくありません。設定画面から入力し直してください。";
        }
    }

    /** OTP 登録画面向けの文言 (iOS の IdPOTPRegistrationFlow.describe と同じ)。 */
    @NonNull
    public String registrationMessage() {
        switch (kind) {
            case MISSING_FIELD:
                return "登録画面が想定した形式ではありませんでした(" + detail + ")。";
            case UNEXPECTED_STATUS:
                return detail + "で想定外の応答(HTTP " + status + ")でした。";
            case OTP_REQUEST_FAILED:
                return "確認コードの送信に失敗しました。";
            case OTP_NOT_CONFIGURED:
                return "このアカウントはワンタイムパスワードが未設定です。";
            case OTP_ALREADY_CONFIGURED:
                return "このアカウントは既にワンタイムパスワードが設定済みです。";
            case UNRECOGNIZED_STATE:
                return "想定外の画面に到達しました(" + detail + ")。学内ネットワークに接続しているか確認してください(OTP登録は学内アクセスからのみ行えます)。";
            case SESSION_TIMED_OUT:
                return "IdP側のセッションがタイムアウトしました。もう一度やり直してください。";
            case INTERACTIVE_LOGIN_REQUIRED:
                return "ワンタイムパスワードの入力が必要です。設定画面から手動でログインしてください。";
            case ACCESS_DENIED:
                return "大学の認証システム(IdP)がアクセスを許可していません(" + detail + ")。";
            case INVALID_CREDENTIALS:
            default:
                return "ユーザー名またはパスワードが正しくありません。設定画面から入力し直してください。";
        }
    }
}
