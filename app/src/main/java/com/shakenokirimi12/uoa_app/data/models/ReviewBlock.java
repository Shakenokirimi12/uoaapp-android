package com.shakenokirimi12.uoa_app.data.models;

/**
 * ブロック一覧の1件 (iOS の ReviewBlock)。相手の投稿者 ID はサーバーが返さない
 * (資格情報として悪用されていたため) ので、ブロック自体の ID と日時だけを持つ。
 */
public class ReviewBlock {
    private final String id;
    private final String createdAt;

    public ReviewBlock(String id, String createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }

    /** "yyyy-MM-dd" 部分。日時が無ければ空文字。 */
    public String getFormattedDate() {
        return createdAt != null && createdAt.length() >= 10 ? createdAt.substring(0, 10) : "";
    }
}
