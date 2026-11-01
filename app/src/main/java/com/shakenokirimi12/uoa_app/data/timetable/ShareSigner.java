package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.GeneralSecurityException;

/**
 * この端末の署名用の鍵 (docs/timetable-share-v3.md の 3・10.2)。本物は AndroidKeyStore (KeyStoreShareSigner)。
 * 共有とフィードの組み立ては、これだけを通して鍵を使う (単体テストではソフトウェアの鍵に差し替える)。
 */
public interface ShareSigner {
    /** 鍵があればその公開鍵 (SPKI DER の base64url)。まだ作っていなければ null (作らない)。 */
    @Nullable
    String existingPublicKey() throws GeneralSecurityException;

    /** 公開鍵。鍵が無ければ作る。 */
    @NonNull
    String publicKey() throws GeneralSecurityException;

    /** data に署名して P1363 (64 バイト) で返す。鍵が無ければ作る。 */
    @NonNull
    byte[] sign(@NonNull byte[] data) throws GeneralSecurityException;

    /**
     * 今の鍵の公開鍵が publicKey のときだけ署名する (鍵は作らない)。違う・無ければ null。確かめてから署名するまでを
     * 一度に行う (その間に「すべてのデータを削除」が鍵を消して、次のユーザーが作り直しても、別の鍵で署名しない)。
     * 一覧の更新・フィードの PUT・削除はこれを使う。鍵を作ってよいのは QR とフィードを作るときだけ。
     */
    @Nullable
    byte[] signIfKeyIs(@NonNull String publicKey, @NonNull byte[] data) throws GeneralSecurityException;

    /** 鍵を消す (すべてのデータを削除)。無ければ何もしない。 */
    void deleteKey() throws GeneralSecurityException;
}
