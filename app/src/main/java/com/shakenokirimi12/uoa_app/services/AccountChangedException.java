package com.shakenokirimi12.uoa_app.services;

import com.shakenokirimi12.uoa_app.R;

/**
 * 取得を始めたアカウントと、いまのアカウント (またはセッションの持ち主) が違う。切り替える前に始まった取得を
 * 止めるために投げる。続けると、前のアカウントでログインし直して切り替えた先のセッションを消すか、別人の
 * データを読む。SessionCoordinator の probe から投げても状態は壊れない (probe は lock と飛行中のログインの外)。
 */
public final class AccountChangedException extends RuntimeException {
    public AccountChangedException() {
        super(AppStrings.get(R.string.account_changed_fetch_cancelled));
    }
}
