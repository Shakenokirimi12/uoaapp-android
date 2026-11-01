package com.shakenokirimi12.uoa_app.ui.idp;

/**
 * ログインで鍵が無いと分かったときの選択 (AuthenticatorKeyChoice) から、登録し直しや取り直しを頼める画面。
 * メイン画面 (MainActivity) と初期設定 (OnboardingActivity) がこれを持つ (iOS はどちらの画面でも同じ選択を出す)。
 */
public interface OtpReregistrationHost {
    /** 登録し直しを始めてよいか。始められないときは、理由をこの中で伝えて false を返す。 */
    boolean canStartReregistration();

    /** 登録し直しの画面を開く。済んだら取り直す。 */
    void openOtpReregistration();

    /** ログインの待ちが時間切れになったあとで、鍵を取り込めた。取り込んだ鍵でログインし直す。 */
    void syncAfterKeyImport();
}
