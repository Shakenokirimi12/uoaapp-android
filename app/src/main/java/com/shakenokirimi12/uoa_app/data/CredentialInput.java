package com.shakenokirimi12.uoa_app.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;

/**
 * ログイン画面の入力を大学のシステムに送る前に整える。
 *
 * 日本語キーボードは ID 欄に全角英数字 (ｓ１２３…) を入れることがあり、パスワード欄でも
 * バックスラッシュが円記号 (¥) になるなどして、正しいパスワードのつもりでも IdP に拒否される
 * (2026-09-27 に実機で発生。同じ ID/PW を PC から送ると受理された)。
 */
public final class CredentialInput {
    private CredentialInput() {}

    /** AINS ID は半角英数字なので、全角を半角に寄せて前後の空白を落とす。 */
    @NonNull
    public static String normalizeUsername(@NonNull String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
    }

    /**
     * パスワードに含まれる ASCII 以外の文字 (最初の 1 つ)。無ければ null。
     * パスワードは勝手に書き換えない (本当にその文字を使っている可能性を消せない) ので、
     * 呼び出し側で確認を出すのに使う。
     */
    @Nullable
    public static String firstNonAscii(@NonNull String password) {
        for (int i = 0; i < password.length(); ) {
            int cp = password.codePointAt(i);
            if (cp > 0x7E || cp < 0x20) return new String(Character.toChars(cp));
            i += Character.charCount(cp);
        }
        return null;
    }

    /** 確認ダイアログの本文。 */
    @NonNull
    public static String nonAsciiWarning(@NonNull String ch) {
        return "パスワードに半角英数字・記号以外の文字「" + ch + "」が含まれています。"
                + "キーボードによっては「\\」が「¥」になるなど、別の文字が入ることがあります。"
                + "パスワード欄の目のアイコンで表示して確かめてください。";
    }
}
