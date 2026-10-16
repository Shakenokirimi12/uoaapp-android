package com.shakenokirimi12.uoa_app.ui.otprelay;

/**
 * 承認通知の本体から開く確認ダイアログ。OtpRelayActivity は QR のリンクを受けるために外部へ公開
 * (exported) しているので、依頼の承認はこちら (非公開) からしか受け付けない。他のアプリが作った
 * Intent で「承認して送る」の確認を出させない。
 */
public class OtpRelayRequestActivity extends OtpRelayActivity {
    @Override
    boolean acceptsRequests() { return true; }
}
