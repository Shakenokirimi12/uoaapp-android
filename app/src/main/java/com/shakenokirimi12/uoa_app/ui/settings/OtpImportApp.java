package com.shakenokirimi12.uoa_app.ui.settings;

import androidx.annotation.StringRes;

import com.shakenokirimi12.uoa_app.R;

/**
 * 取り込みの案内を出す認証アプリ。iOS の AuthenticatorApp と同じ (Aegis と Samsung Pass は Android だけ、
 * Apple の「パスワード」は iOS だけ)。何ができるかは各アプリの公式の案内で確かめた範囲 (SPEC.md 3.3)。
 * 鍵を取り出せない、または取り出し方を確かめられていないアプリは、登録し直しを案内する。
 */
enum OtpImportApp {
    GOOGLE(R.string.otp_import_app_google, R.string.otp_import_guide_google, Method.QR),
    ENTE(R.string.otp_import_app_ente, R.string.otp_import_guide_ente, Method.QR),
    AEGIS(R.string.otp_import_app_aegis, R.string.otp_import_guide_aegis, Method.QR),
    TWO_FAS(R.string.otp_import_app_twofas, R.string.otp_import_guide_twofas, Method.PASTE),
    ONE_PASSWORD(R.string.otp_import_app_onepassword, R.string.otp_import_guide_onepassword, Method.PASTE),
    BITWARDEN(R.string.otp_import_app_bitwarden, R.string.otp_import_guide_bitwarden, Method.PASTE),
    MICROSOFT(R.string.otp_import_app_microsoft, R.string.otp_import_guide_microsoft, Method.NONE),
    AUTHY(R.string.otp_import_app_authy, R.string.otp_import_guide_authy, Method.NONE),
    SAMSUNG(R.string.otp_import_app_samsung, R.string.otp_import_guide_samsung, Method.NONE),
    IIJ(R.string.otp_import_app_iij, R.string.otp_import_guide_iij, Method.NONE),
    OTHER(R.string.otp_import_app_other, R.string.otp_import_guide_other, Method.QR_OR_PASTE);

    enum Method { QR, PASTE, QR_OR_PASTE, NONE }

    @StringRes final int name;
    @StringRes final int guide;
    final Method method;

    OtpImportApp(@StringRes int name, @StringRes int guide, Method method) {
        this.name = name;
        this.guide = guide;
        this.method = method;
    }

    boolean readsQr() {
        return method == Method.QR || method == Method.QR_OR_PASTE;
    }

    boolean readsPaste() {
        return method == Method.PASTE || method == Method.QR_OR_PASTE;
    }
}
