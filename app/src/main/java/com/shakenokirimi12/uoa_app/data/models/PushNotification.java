package com.shakenokirimi12.uoa_app.data.models;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PushNotification {
    private String id;
    private String title;
    private String body;
    private String url;
    private String priority;
    private String expires_at;
    private String created_at;
    private int is_read;

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getUrl() { return url; }
    public String getPriority() { return priority; }
    public String getCreatedAt() { return created_at; }
    public boolean isRead() { return is_read == 1; }

    public Date getCreatedDate() {
        if (created_at == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            return fmt.parse(created_at);
        } catch (Exception e) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                return fmt.parse(created_at);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
