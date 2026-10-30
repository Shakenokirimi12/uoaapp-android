package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * ログアウトの前に始まった裏の同期は、取れた結果を空にしたキャッシュに書き戻さない。ログアウトのあとに始めた
     * 同期は保存する。
     */
    @Test
    public void syncStartedBeforeLogout_doesNotSave() {
        AccountSwitch.Ticket before = AccountSwitch.ticket();

        AccountSwitch.loggedOut();

        AtomicBoolean saved = new AtomicBoolean();
        assertFalse(AccountSwitch.runIfCurrent(before, () -> saved.set(true)));
        assertFalse(saved.get());
        assertTrue(AccountSwitch.runIfCurrent(AccountSwitch.ticket(), () -> saved.set(true)));
        assertTrue(saved.get());
    }

    /**
     * 切り替えの確認のログインで読んだ方式は、そのアカウントに決まったときだけ取り出せる。別のアカウントに決まった
     * ときと、取り出したあとは null。
     */
    @Test
    public void pendingOtpMethod_isTakenOnlyForTheVerifiedAccount() {
        AccountSwitch.notePendingOtpMethod("s1300001", OtpMethod.AUTHENTICATOR);
        assertNull(AccountSwitch.takePendingOtpMethod("s1300002"));
        assertNull(AccountSwitch.takePendingOtpMethod("s1300001"));

        AccountSwitch.notePendingOtpMethod("s1300001", OtpMethod.AUTHENTICATOR);
        assertEquals(OtpMethod.AUTHENTICATOR, AccountSwitch.takePendingOtpMethod("s1300001"));
        assertNull(AccountSwitch.takePendingOtpMethod("s1300001"));
    }
}
