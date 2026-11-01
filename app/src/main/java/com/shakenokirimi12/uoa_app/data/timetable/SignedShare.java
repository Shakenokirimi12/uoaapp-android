package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

/**
 * 署名付きの共有 (v3)。`{"v":3,"timetable":"<base64url>","publicKey":"<base64url>","signature":"<base64url>"}`。
 * 時間割は JSON のバイト列を base64url にして文字列で運ぶ (並べ直しで署名が壊れないように)。
 * 署名の対象は "uoa-timetable-share-v3\n" + そのバイト列。サーバーの verifyShareV3 と同じ規則で確かめる。
 */
public final class SignedShare {
    public static final int VERSION = 3;

    @NonNull public final String timetable;
    @NonNull public final String publicKey;
    @NonNull public final String signature;

    public SignedShare(@NonNull String timetable, @NonNull String publicKey, @NonNull String signature) {
        this.timetable = timetable;
        this.publicKey = publicKey;
        this.signature = signature;
    }

    /** 確かめた共有と、署名された時間割 (SharedTimetable.normalize を通したもの)。 */
    public static final class Verified {
        @NonNull public final SignedShare share;
        @NonNull public final SharedTimetable timetable;

        Verified(@NonNull SignedShare share, @NonNull SharedTimetable timetable) {
            this.share = share;
            this.timetable = timetable;
        }
    }

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("timetable", timetable);
        o.addProperty("publicKey", publicKey);
        o.addProperty("signature", signature);
        return o;
    }

    /** 自分の時間割に署名する。createdAt は作ったときの時刻 (サーバーは 24 時間より離れたものを受けない)。 */
    @NonNull
    public static SignedShare sign(@NonNull SharedTimetable timetable, @NonNull ShareSigner signer) throws GeneralSecurityException {
        byte[] bytes = timetable.encode().getBytes(StandardCharsets.UTF_8);
        String key = signer.publicKey();
        byte[] signature = signer.sign(ShareCrypto.shareSigningInput(bytes));
        return new SignedShare(ShareCrypto.base64url(bytes), key, ShareCrypto.base64url(signature));
    }

    /** 今ある鍵だけで署名する (作らない)。鍵が無い・途中で変わったときは null。 */
    @Nullable
    public static SignedShare signWithExistingKey(@NonNull SharedTimetable timetable, @NonNull ShareSigner signer)
            throws GeneralSecurityException {
        String key = signer.existingPublicKey();
        if (key == null) return null;
        byte[] bytes = timetable.encode().getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signIfKeyIs(key, ShareCrypto.shareSigningInput(bytes));
        if (signature == null) return null;
        return new SignedShare(ShareCrypto.base64url(bytes), key, ShareCrypto.base64url(signature));
    }

    /** 形・公開鍵・署名・時間割のどれかが違えば null。 */
    @Nullable
    public static Verified verify(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        JsonElement v = o.get("v");
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
        try {
            if (v.getAsJsonPrimitive().getAsDouble() != VERSION) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        String timetable = string(o.get("timetable"));
        String publicKey = string(o.get("publicKey"));
        String signature = string(o.get("signature"));
        if (timetable == null || publicKey == null || signature == null) return null;
        byte[] bytes = ShareCrypto.base64urlDecode(timetable);
        if (bytes == null) return null;
        PublicKey key = ShareCrypto.parsePublicKey(publicKey);
        if (key == null) return null;
        if (!ShareCrypto.verify(key, signature, ShareCrypto.shareSigningInput(bytes))) return null;
        String json;
        try {
            // サーバーと同じく、壊れた UTF-8 は読まない (置き換え文字にしない)。
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        SharedTimetable t;
        try {
            t = SharedTimetable.normalize(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return null;
        }
        return t != null ? new Verified(new SignedShare(timetable, publicKey, signature), t) : null;
    }

    @Nullable
    private static String string(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }
}
