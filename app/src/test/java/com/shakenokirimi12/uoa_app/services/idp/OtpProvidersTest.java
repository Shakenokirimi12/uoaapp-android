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

    /** otp_type の解釈は 1 か所 (OtpMethod.fromOtpType)。"mail" だけがメール、ほかの値は認証アプリ。 */
    @Test
    public void fromOtpType_mapsMailAndOtherValues() {
        assertEquals(OtpMethod.MAIL, OtpMethod.fromOtpType("mail"));
        assertEquals(OtpMethod.MAIL, OtpMethod.fromOtpType(" MAIL "));
        assertEquals(OtpMethod.MAIL, OtpMethod.fromOtpType("Mail\t"));
        assertEquals(OtpMethod.AUTHENTICATOR, OtpMethod.fromOtpType("totp"));
    }

    /** 読めない値や「未設定」の印では決めない (控えの方式で進み、保存しない)。 */
    @Test
    public void fromOtpType_unknownValues_returnNull() {
        assertNull(OtpMethod.fromOtpType(null));
        assertNull(OtpMethod.fromOtpType(""));
        assertNull(OtpMethod.fromOtpType("none"));
        assertNull(OtpMethod.fromOtpType(" none "));
        assertNull(OtpMethod.fromOtpType("NONE"));
    }

    /** 診断に載せるのは otp_type の値だけ。無い・空を区別し、記号は伏せて 32 文字まで。 */
    @Test
    public void otpTypeLabel_distinguishesAbsentAndEmpty_andSanitizes() {
        assertEquals("(absent)", OtpProviders.otpTypeLabel(null));
        assertEquals("(empty)", OtpProviders.otpTypeLabel("  "));
        assertEquals("mail", OtpProviders.otpTypeLabel("mail"));
        assertEquals("a?b", OtpProviders.otpTypeLabel("a=b"));
        assertEquals(32, OtpProviders.otpTypeLabel("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").length());
    }

    /** 送ったコードと同じコードのメールだけ、IdP が受け付けたあとに消す (空白は無視)。 */
    @Test
    public void shouldDeleteAfterAccepted_onlyWhenTheSentCodeMatchesTheMail() {
        assertTrue(OtpProviders.shouldDeleteAfterAccepted("123456", "123456"));
        assertTrue(OtpProviders.shouldDeleteAfterAccepted("123456", " 123 456 "));
        assertFalse(OtpProviders.shouldDeleteAfterAccepted("123456", "654321"));
        assertFalse(OtpProviders.shouldDeleteAfterAccepted(null, "123456"));
        assertFalse(OtpProviders.shouldDeleteAfterAccepted("", ""));
    }

    private static final String OTPAUTH = "otpauth://totp/SECIOSS:s1300000?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=SECIOSS";

    /** メール方式もバックグラウンドでログインする (送信の回数は BackgroundLoginPolicy が 6 時間に 1 回に抑える)。 */
    @Test
    public void mail_canRunUnattended() {
        assertTrue(OtpProviders.of(OtpMethod.MAIL, "s1300000", "pw", AuthorizationContext.BACKGROUND).canRunUnattended());
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

    /** 作ったコードが (やり直しを含めて) 弾かれたら鍵を止める。手入力のコードが弾かれても止めない。 */
    @Test
    public void rejectedGeneratedCode_suspendsTheKey_butTypedCodeDoesNot() throws Exception {
        java.util.List<String> suspended = new java.util.ArrayList<>();
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.BACKGROUND, () -> OTPAUTH,
                TotpStepLedger.inMemory(), suspended::add);
        p.onRejected();
        assertTrue("コードを作る前は止めない", suspended.isEmpty());
        p.code();
        p.onRejected();
        assertEquals(java.util.Collections.singletonList("s1300000"), suspended);
    }

    /**
     * 時計が戻って台帳の窓が今より先にあると、使える窓は来ない。前面は待たずに手入力に回し (作ったコードではないので
     * 弾かれても鍵を止めない)、バックグラウンドは止めた印を立てない TOTP_STEP_UNAVAILABLE で諦める。
     */
    @Test
    public void ledgerAheadOfTheClock_fallsBackToManualInForeground_andSkipsInBackground() throws Exception {
        long ahead = Totp.step(Totp.parse(OTPAUTH), System.currentTimeMillis()) + 10;
        java.util.List<String> suspended = new java.util.ArrayList<>();

        TotpStepLedger ledger = TotpStepLedger.inMemory();
        ledger.claim(ahead);
        // 鍵はあるので、取り込み・登録し直しの選択は出さない (使える窓が無いだけ)。
        OtpProvider foreground = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.INTERACTIVE,
                () -> OTPAUTH, ledger, suspended::add, ctx -> "123456",
                (u, ctx) -> { throw new AssertionError("a stored key must not offer the no-key choice"); });
        long started = System.currentTimeMillis();
        assertEquals("123456", foreground.code());
        assertTrue("待たない", System.currentTimeMillis() - started < 2_000);
        assertFalse(foreground.retriesRejectedCode());
        foreground.onRejected();
        assertTrue(suspended.isEmpty());

        OtpProvider background = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.BACKGROUND,
                () -> OTPAUTH, ledger, suspended::add, ctx -> { throw new AssertionError("background must not prompt"); });
        try {
            background.code();
            fail();
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.TOTP_STEP_UNAVAILABLE, e.kind);
        }
    }

    /** 鍵が無いときは選択 (AuthenticatorKeyChoice) を通し、手入力を選んだらそのコードを送る。弾かれても鍵は止めない。 */
    @Test
    public void authenticatorWithoutKey_foreground_usesTheCodeTypedFromTheChoice() throws Exception {
        java.util.List<String> suspended = new java.util.ArrayList<>();
        java.util.List<String> asked = new java.util.ArrayList<>();
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.INTERACTIVE,
                () -> null, TotpStepLedger.inMemory(), suspended::add,
                ctx -> { throw new AssertionError("manual entry goes through the choice"); },
                (u, ctx) -> {
                    asked.add(u);
                    return "654321";
                });
        assertEquals("654321", p.code());
        assertEquals(java.util.Collections.singletonList("s1300000"), asked);
        assertFalse(p.retriesRejectedCode());
        p.onRejected();
        assertTrue(suspended.isEmpty());
    }

    /**
     * 選択で鍵を取り込んだら、何も打たせずに取り込んだ鍵でコードを作る。以後は保存した鍵と同じ扱い
     * (台帳に窓を記録し、弾かれたら次の窓でやり直す)。
     */
    @Test
    public void authenticatorWithoutKey_importedFromTheChoice_generatesTheCodeFromTheNewKey() throws Exception {
        String[] stored = {null};
        TotpStepLedger ledger = TotpStepLedger.inMemory();
        OtpProvider p = new OtpProviders.AuthenticatorOtpProvider("s1300000", AuthorizationContext.INTERACTIVE,
                () -> stored[0], ledger, owner -> { },
                ctx -> { throw new AssertionError("an imported key must not ask for a code"); },
                (u, ctx) -> {
                    stored[0] = OTPAUTH;
                    return null;
                });
        String code = p.code();
        assertTrue(Totp.matches(Totp.parse(OTPAUTH), code, System.currentTimeMillis()));
        assertTrue(p.retriesRejectedCode());
        assertFalse("used step is recorded", ledger.claim(Totp.step(Totp.parse(OTPAUTH), System.currentTimeMillis()) - 1));
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

    /** 周期の違う鍵に替えたら、使った窓の終わりの時刻で番号を直す (iOS の testLedgerRescalesWhenThePeriodChanges と同じ)。 */
    @Test
    public void ledgerRescalesWhenThePeriodChanges() {
        TotpStepLedger ledger = TotpStepLedger.inMemory();
        assertTrue(ledger.claim(100));
        ledger.rescale(30, 60);
        assertFalse(ledger.claim(50));
        assertTrue(ledger.claim(51));
        ledger.rescale(60, 30);
        assertFalse(ledger.claim(103));
        assertTrue(ledger.claim(104));
    }
}
