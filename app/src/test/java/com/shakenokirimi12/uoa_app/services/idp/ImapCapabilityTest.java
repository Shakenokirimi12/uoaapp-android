package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** UID EXPUNGE は UIDPLUS があるときだけ使う。無いのに素の EXPUNGE で代えると、他の削除済みメールまで消える。 */
public class ImapCapabilityTest {
    @Test
    public void findsUidPlusInTheCapabilityLine() {
        assertTrue(ImapOtpFetcher.hasCapability(Arrays.asList(
                "* CAPABILITY IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE SORT UIDPLUS MOVE"), "UIDPLUS"));
        assertTrue(ImapOtpFetcher.hasCapability(Arrays.asList("* capability imap4rev1 uidplus"), "UIDPLUS"));
    }

    @Test
    public void doesNotMatchPartsOfOtherWordsOrOtherLines() {
        assertFalse(ImapOtpFetcher.hasCapability(Arrays.asList(
                "* CAPABILITY IMAP4rev1 XUIDPLUSX", "* OK UIDPLUS mentioned in text"), "UIDPLUS"));
        assertFalse(ImapOtpFetcher.hasCapability(Collections.emptyList(), "UIDPLUS"));
    }
}
