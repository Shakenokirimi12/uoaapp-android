package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountSwitchTest {
    /**
     * 入口の確認を通ったあとに切り替えが始まった取得は、結果を渡す前に古いと分かる。切り替えのあとに始めた取得は
     * 今のアカウントのもの。
     */
    @Test
    public void ticketTakenBeforeSwitch_isStaleAfterIt() {
        AccountSwitch.Ticket before = AccountSwitch.ticket();
        assertTrue(before.isCurrent());

        AccountSwitch.advance();

        assertFalse(before.isCurrent());
        assertTrue(AccountSwitch.ticket().isCurrent());
    }
}
