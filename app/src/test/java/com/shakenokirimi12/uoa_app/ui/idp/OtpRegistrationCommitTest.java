package com.shakenokirimi12.uoa_app.ui.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

import org.junit.Test;

/**
 * OTP の受け取り方は、大学側 (IdP) への登録が済んだと分かったときにだけ書き換える。
 * 中断・失敗・IdP が切り替わっていないときは、今の受け取り方のまま。
 */
public class OtpRegistrationCommitTest {

    /** 書き込まれた方式を覚えるだけ。 */
    private static final class Store implements OtpRegistrationCommit.MethodWriter {
        @Nullable OtpMethod written;
        @Override public void apply(OtpMethod method) { written = method; }
    }

    /** 決まった otp_type を返し、読まれた回数を数える。 */
    private static final class Reader implements OtpRegistrationCommit.OtpTypeReader {
        @Nullable final String value;
        int reads;
        Reader(@Nullable String value) { this.value = value; }
        @Nullable @Override public String read() { reads++; return value; }
    }

    @Test
    public void switching_writesNothingUntilRegistrationCompletes() {
        Store store = new Store();
        OtpRegistrationCommit commit = new OtpRegistrationCommit(OtpMethod.AUTHENTICATOR, store, new Reader("totp"));

        assertTrue(commit.tryBeginPost());
        assertNull(store.written);

        assertEquals(OtpRegistrationCommit.Result.SUCCEEDED, commit.afterCompletion(OtpMethod.AUTHENTICATOR));
        assertEquals(OtpMethod.AUTHENTICATOR, store.written);
    }

    /** 初回登録で認証アプリを選んだら、受け取り方も認証アプリにする (以前は mail のまま残っていた)。 */
    @Test
    public void firstRegistration_writesTheRegisteredMethod_withoutReadingOtpType() {
        Store store = new Store();
        Reader reader = new Reader("mail");
        OtpRegistrationCommit commit = new OtpRegistrationCommit(null, store, reader);

        assertEquals(OtpRegistrationCommit.Result.SUCCEEDED, commit.afterCompletion(OtpMethod.AUTHENTICATOR));
        assertEquals(OtpMethod.AUTHENTICATOR, store.written);
        assertEquals(0, reader.reads);
    }

    /** IdP が完了と返しても、まだ前の方式でログインさせるなら書き換えない。 */
    @Test
    public void switching_keepsMethod_whenIdpStillUsesTheOldOne() {
        Store store = new Store();
        OtpRegistrationCommit commit = new OtpRegistrationCommit(OtpMethod.AUTHENTICATOR, store, new Reader("mail"));

        assertEquals(OtpRegistrationCommit.Result.NOT_SWITCHED, commit.afterCompletion(OtpMethod.AUTHENTICATOR));
        assertNull(store.written);
    }

    /** 学内にいる・通信に失敗した等で otp_type が読めなければ、IdP の「設定が完了しました」を信じる。 */
    @Test
    public void switching_trustsCompletion_whenOtpTypeIsUnreadable() {
        Store store = new Store();
        OtpRegistrationCommit commit = new OtpRegistrationCommit(OtpMethod.MAIL, store, new Reader(null));

        assertEquals(OtpRegistrationCommit.Result.SUCCEEDED, commit.afterCompletion(OtpMethod.MAIL));
        assertEquals(OtpMethod.MAIL, store.written);
    }

    @Test
    public void failure_keepsMethod() {
        Store store = new Store();
        assertEquals(OtpRegistrationCommit.Result.FAILED,
                new OtpRegistrationCommit(OtpMethod.AUTHENTICATOR, store, new Reader(null)).afterError(OtpMethod.AUTHENTICATOR));
        assertEquals(OtpRegistrationCommit.Result.FAILED,
                new OtpRegistrationCommit(OtpMethod.AUTHENTICATOR, store, new Reader("mail")).afterError(OtpMethod.AUTHENTICATOR));
        assertEquals(OtpRegistrationCommit.Result.FAILED,
                new OtpRegistrationCommit(null, store, new Reader("mail")).afterError(OtpMethod.MAIL));
        assertNull(store.written);
    }

    /** 完了の応答だけ失われ、IdP はもう切り替え先でログインさせるなら、成功として書き換える。 */
    @Test
    public void failure_butIdpAlreadySwitched_writesTheNewMethod() {
        Store store = new Store();
        OtpRegistrationCommit commit = new OtpRegistrationCommit(OtpMethod.MAIL, store, new Reader("mail"));

        assertEquals(OtpRegistrationCommit.Result.SUCCEEDED, commit.afterError(OtpMethod.MAIL));
        assertEquals(OtpMethod.MAIL, store.written);
    }

    /** 閉じたあとは POST を送らない。送り始めたあとに閉じても止めさせない。 */
    @Test
    public void dismissBeforePost_sendsNothing_dismissAfterPost_letsItFinish() {
        OtpRegistrationCommit closedEarly = new OtpRegistrationCommit(OtpMethod.MAIL, new Store(), new Reader(null));
        assertFalse(closedEarly.dismiss());
        assertFalse(closedEarly.tryBeginPost());

        OtpRegistrationCommit closedLate = new OtpRegistrationCommit(OtpMethod.MAIL, new Store(), new Reader(null));
        assertTrue(closedLate.tryBeginPost());
        assertTrue(closedLate.dismiss());
    }

    @Test
    public void idpUsesMethod_matchesOnlyOnMail() {
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.MAIL, "mail"));
        assertFalse(OtpRegistrationCommit.idpUsesMethod(OtpMethod.AUTHENTICATOR, "mail"));
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.AUTHENTICATOR, "totp"));
        assertFalse(OtpRegistrationCommit.idpUsesMethod(OtpMethod.MAIL, "totp"));
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.AUTHENTICATOR, null));
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.AUTHENTICATOR, ""));
        // 「未設定」の印は方式を表さない (読めない値と同じく、登録した方式をそのまま信じる)。
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.MAIL, "none"));
        assertTrue(OtpRegistrationCommit.idpUsesMethod(OtpMethod.AUTHENTICATOR, " none "));
    }

    @Test
    public void reRegisteringTheSameMethod_doesNotGuessSuccessFromOtpType() {
        Store store = new Store();
        OtpRegistrationCommit commit = new OtpRegistrationCommit(
                OtpMethod.AUTHENTICATOR, OtpMethod.AUTHENTICATOR, store, new Reader("totp"));
        assertEquals(OtpRegistrationCommit.Result.FAILED, commit.afterError(OtpMethod.AUTHENTICATOR));
        assertEquals(OtpRegistrationCommit.Result.SUCCEEDED, commit.afterCompletion(OtpMethod.AUTHENTICATOR));
    }
}
