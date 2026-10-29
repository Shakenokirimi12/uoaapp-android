package com.shakenokirimi12.uoa_app.ui.idp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 貼り付けたコードの空白 (全角を含む) は送る前に落とす (S-3)。 */
public class OtpPromptDialogTest {
    @Test
    public void normalizedCode_dropsHalfAndFullWidthSpaces() {
        assertEquals("123456", OtpPromptDialog.normalizedCode(" 123456\n"));
        assertEquals("123456", OtpPromptDialog.normalizedCode("123 456"));
        assertEquals("123456", OtpPromptDialog.normalizedCode("　123456　"));
        assertEquals("", OtpPromptDialog.normalizedCode("   "));
    }
}
