package com.shakenokirimi12.uoa_app.services;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.shakenokirimi12.uoa_app.UoaApplication;

/**
 * Context を持たないサービス層 (ログイン・IdP・OTP の中継) から strings.xml の文言を引く。
 *
 * 文言は例外のメッセージとして画面まで運ばれ、同じ文言かどうかで種類を見分けている箇所がある
 * (AuthErrors.isInvalidCredentials など)。比べる両側がここを通るので、端末でもテストでも一致する。
 */
public final class AppStrings {
    private AppStrings() {}

    @NonNull
    public static String get(@StringRes int id, Object... args) {
        Context context = UoaApplication.getAppContext();
        if (context == null) {
            // Application が無いのは JVM の単体テストだけ (端末では onCreate より前にサービスは動かない)。
            // 文言の代わりに ID を返す。画面には届かず、同じ ID 同士の比較はテストでも一致する。
            return args.length == 0 ? "@string/" + id : "@string/" + id + java.util.Arrays.toString(args);
        }
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }
}
