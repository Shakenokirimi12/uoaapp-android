package com.shakenokirimi12.uoa_app.ui.idp;

import static org.junit.Assert.assertEquals;

import com.shakenokirimi12.uoa_app.ui.idp.AuthenticatorKeyActions.ClipAction;

import org.junit.Test;

/** コピーしたキーを 2 分で消すときの判断。別のアプリでコピーしたものは消さない。 */
public class AuthenticatorKeyActionsTest {
    private static final long EXPIRES = 1_000_000L;

    @Test
    public void nothingToClearWithoutACopy() {
        assertEquals(ClipAction.NONE, AuthenticatorKeyActions.clipAction(EXPIRES + 1, 0, true));
    }

    @Test
    public void waitsUntilTheDeadline() {
        assertEquals(ClipAction.WAIT, AuthenticatorKeyActions.clipAction(EXPIRES - 1, EXPIRES, true));
    }

    @Test
    public void clearsOnlyItsOwnKeyAfterTheDeadline() {
        assertEquals(ClipAction.CLEAR, AuthenticatorKeyActions.clipAction(EXPIRES, EXPIRES, true));
        assertEquals(ClipAction.FORGET, AuthenticatorKeyActions.clipAction(EXPIRES, EXPIRES, false));
    }

    /** 前面にいなくてクリップボードを読めない間は、消さずに次の機会を待つ。 */
    @Test
    public void waitsWhileTheClipboardCannotBeRead() {
        assertEquals(ClipAction.WAIT, AuthenticatorKeyActions.clipAction(EXPIRES + 60_000, EXPIRES, null));
    }
}
