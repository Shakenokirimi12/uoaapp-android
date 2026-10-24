package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.shakenokirimi12.uoa_app.services.AuthorizationContext;

import org.junit.Test;

/** 受け取り方が決まっていない端末で、OTP 入力画面の otp_type から IdP の方式を読む。 */
public class OtpProvidersTest {

    @Test
    public void methodFromOtpType_mapsMailAndOtherValues() {
        assertEquals(OtpMethod.MAIL, OtpProviders.methodFromOtpType("mail"));
        assertEquals(OtpMethod.AUTHENTICATOR, OtpProviders.methodFromOtpType("totp"));
    }

    /** 読めない値や「未設定」の印では決めない (既定のメールのまま進み、保存しない)。 */
    @Test
    public void methodFromOtpType_unknownValues_returnNull() {
        assertNull(OtpProviders.methodFromOtpType(null));
        assertNull(OtpProviders.methodFromOtpType(""));
        assertNull(OtpProviders.methodFromOtpType("none"));
    }

    private static final String OTPAUTH = "otpauth://totp/SECIOSS:s1300000?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=SECIOSS";

    /** メール方式はバックグラウンドの新規ログインを始めさせない (ログイン 1 回 = OTP メール 1 通)。 */
    @Test
    public void mail_cannotRunUnattended() {
        assertFalse(OtpProviders.of(OtpMethod.MAIL, "s1300000", "pw", AuthorizationContext.BACKGROUND).canRunUnattended());
    }

    /** 鍵を持つ認証アプリ方式は、バックグラウンドでも鍵からコードを作る (入力は求めない)。 */
    @Test
    public void authenticatorWithKey_generatesCodeInBackground() throws Exception {
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.BACKGROUND, () -> OTPAUTH);
        assertTrue(p.canRunUnattended());
        String code = p.code();
        assertTrue(Totp.matches(Totp.parse(OTPAUTH), code, System.currentTimeMillis()));
    }

    /**
     * 鍵で作ったコードは、弾かれても同じログインの中で次の窓で 1 回やり直す (1 回目で鍵を捨てない)。
     * やり直しの code() は、使用済みの窓を避けて次の窓のコードを作る。
     */
    @Test
    public void authenticatorWithKey_retriesARejectedCodeWithTheNextStep() throws Exception {
        TotpStepLedger ledger = TotpStepLedger.inMemory();
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.BACKGROUND, () -> OTPAUTH, ledger);
        assertFalse(p.retriesRejectedCode());
        p.code();
        assertTrue(p.retriesRejectedCode());
        // 使った窓は台帳に入っている (それより前の窓は使えない)。
        assertFalse(ledger.claim(Totp.step(Totp.parse(OTPAUTH), System.currentTimeMillis()) - 1));
    }

    /** 台帳は新しい窓だけを受け付ける (中継とログインで同じ窓を二重に使わない)。 */
    @Test
    public void stepLedger_acceptsOnlyNewerSteps() {
        TotpStepLedger ledger = TotpStepLedger.inMemory();
        assertTrue(ledger.claim(10));
        assertFalse(ledger.claim(10));
        assertFalse(ledger.claim(9));
        assertTrue(ledger.claim(11));
    }

    /** 鍵が無ければ (登録し直しが要る)、バックグラウンドではコードを用意できない。 */
    @Test
    public void authenticatorWithoutKey_refusesInBackground() {
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.BACKGROUND, () -> null);
        assertFalse(p.canRunUnattended());
        try {
            p.code();
            fail("background must not prompt");
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, e.kind);
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }
}
