package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CredentialInputTest {
    @Test
    public void fullWidthUsername_isNormalizedToAscii() {
        assertEquals("s1340183", CredentialInput.normalizeUsername(" ｓ１３４０１８３　"));
        assertEquals("s1340183", CredentialInput.normalizeUsername("s1340183"));
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
