package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
