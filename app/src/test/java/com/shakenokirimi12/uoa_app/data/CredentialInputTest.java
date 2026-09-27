package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CredentialInputTest {
    @Test
    public void fullWidthUsername_isNormalizedToAscii() {
        assertEquals("s1234567", CredentialInput.normalizeUsername(" ｓ１２３４５６７　"));
        assertEquals("s1234567", CredentialInput.normalizeUsername("s1234567"));
    }

    @Test
    public void asciiPassword_hasNoWarning() {
        assertNull(CredentialInput.firstNonAscii("Abc!@#$%^&*()_+-=[]{}\\|;:'\",.<>/?`~ 09"));
    }

    @Test
    public void yenSignOrFullWidth_isReported() {
        assertEquals("¥", CredentialInput.firstNonAscii("pass¥word"));
        assertEquals("！", CredentialInput.firstNonAscii("pass！"));
    }
}
