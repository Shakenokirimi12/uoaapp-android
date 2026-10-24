package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/** 問い合わせ ID の形 (サーバーの normalizeSupportId が受ける XXXX-XXXX) と、端末の外へ持ち出さないこと。 */
public class SupportIdTest {

    @Test
    public void generatedIdsUseCrockfordBase32InTheDisplayedForm() {
        Random random = new Random(1);
        for (int i = 0; i < 500; i++) {
            String id = SupportId.generate(random);
            assertEquals(id, 9, id.length());
            assertEquals(id, '-', id.charAt(4));
            assertTrue(id, SupportId.isValid(id));
            assertFalse(id, id.matches(".*[ILOU].*"));
        }
        assertNotEquals(SupportId.generate(), SupportId.generate());
    }

    @Test
    public void validationMatchesTheServerForm() {
        assertTrue(SupportId.isValid("7K3M-9QXA"));
        for (String bad : new String[] {null, "", "7K3M9QXA", "7k3m-9qxa", "7K3M-9QXU", "7K3M-9QX", "7K3M-9QXAB", "7K3M-9QXA\n"}) {
            assertFalse(String.valueOf(bad), SupportId.isValid(bad));
        }
    }

    @Test
    public void theIdStaysOnThisDevice() {
        assertTrue(Arrays.asList(PreferenceManager.DEVICE_LOCAL_KEYS).contains("support_id"));
    }
}
