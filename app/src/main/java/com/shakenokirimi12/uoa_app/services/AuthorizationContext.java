package com.shakenokirimi12.uoa_app.services;

/**
 * ログインを開始してよい文脈か。
 *
 * 以前は {@code boolean userInitiated} を呼び出しごとに引き回していたため、渡し忘れた経路が
 * そのまま「バックグラウンドから新規ログインできる」抜け道になっていた。既定値を持つ
 * オーバーロードは用意しない。呼び出し側に必ずどちらかを書かせる。
 *
 * ログイン 1 回につき OTP メールが 1 通飛ぶ。画面を見ていない間にそれをやると、寝ている間に
 * メールが積み上がる (iOS で 2026-09-25 に発生。原因と対策は docs/auth-rewrite/ を参照)。
 */
public enum AuthorizationContext {
    /** ユーザーが画面で操作した。新規ログインしてよい。OTP の入力を求めてよい。 */
    INTERACTIVE,
    /** 定期同期・ウィジェット。保存済みセッションの再利用だけ許す。新規ログインはしない。 */
    BACKGROUND;

    public boolean allowsNewLogin() {
        return this == INTERACTIVE;
    }
}
