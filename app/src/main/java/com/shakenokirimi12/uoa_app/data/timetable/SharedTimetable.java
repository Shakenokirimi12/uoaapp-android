package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 時間割共有の形式 (format "uoa-timetable", version 1)。
 *
 * iOS (Models/TimetableShare.swift) とサーバー (uoa-app-push src/timetable-share.ts) と同じ規則で読み書きする。
 * 例と不正な例は共通のフィクスチャ (app/src/test/resources/timetable-share-v1.json。3 か所に同じ内容)。
 * 共有するのは曜日・時限・授業名・教室と、送り手が付けた名前だけ。成績・課題・ID は入れない。
 * JSON は Gson で読む (org.json は JVM の単体テストで動かないため)。
 */
public final class SharedTimetable {
    public static final String FORMAT = "uoa-timetable";
    public static final int VERSION = 1;
    public static final int MAX_NAME = 40;
    public static final int MAX_TITLE = 100;
    public static final int MAX_ROOM = 50;
    public static final int MAX_CLASSES = 100;
    public static final int MAX_DAY = 7;
    public static final int MAX_PERIOD = 11;

    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @NonNull public final String name;
    /** ISO 8601 (UTC、秒まで)。 */
    @NonNull public final String createdAt;
    @NonNull public final List<SharedClass> classes;

    private SharedTimetable(@NonNull String name, @NonNull String createdAt, @NonNull List<SharedClass> classes) {
        this.name = name;
        this.createdAt = createdAt;
        this.classes = Collections.unmodifiableList(classes);
    }

    /** 自分の時間割を作る。名前は送る前の規則で整える。 */
    @NonNull
    public static SharedTimetable create(@NonNull String name, long createdAtMs, @NonNull List<SharedClass> classes) {
        return new SharedTimetable(sanitize(name, MAX_NAME),
                ISO_SECONDS.format(Instant.ofEpochMilli(createdAtMs).truncatedTo(ChronoUnit.SECONDS)),
                new ArrayList<>(classes));
    }

    /** 受け取った時刻 (ミリ秒)。読めなければ 0。 */
    public long createdAtMillis() {
        Instant i = parseInstant(createdAt);
        return i != null ? i.toEpochMilli() : 0;
    }

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("version", VERSION);
        o.addProperty("name", name);
        o.addProperty("createdAt", createdAt);
        JsonArray list = new JsonArray();
        for (SharedClass c : classes) {
            JsonObject j = new JsonObject();
            j.addProperty("day", c.day);
            j.addProperty("start", c.start);
            j.addProperty("end", c.end);
            j.addProperty("title", c.title);
            j.addProperty("room", c.room);
            list.add(j);
        }
        o.add("classes", list);
        return o;
    }

    @NonNull
    public String encode() { return toJson().toString(); }

    @Nullable
    public static SharedTimetable decode(@Nullable String json) {
        if (json == null) return null;
        try {
            return normalize(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** JSON を共有してよい形に作り直す。形が違えば null (知らない項目は捨てる)。サーバーの normalizeTimetable と同じ規則。 */
    @Nullable
    public static SharedTimetable normalize(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject t = element.getAsJsonObject();
        if (!FORMAT.equals(string(t.get("format")))) return null;
        Integer version = integer(t.get("version"));
        if (version == null || version != VERSION) return null;
        String name = "";
        if (t.has("name")) {
            String raw = string(t.get("name"));
            if (raw == null) return null;
            name = sanitize(raw, MAX_NAME);
        }
        String createdRaw = string(t.get("createdAt"));
        Instant created = createdRaw != null ? parseInstant(createdRaw) : null;
        if (created == null) return null;
        JsonElement list = t.get("classes");
        if (list == null || !list.isJsonArray()) return null;
        JsonArray array = list.getAsJsonArray();
        if (array.size() == 0 || array.size() > MAX_CLASSES) return null;
        List<SharedClass> classes = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) return null;
            JsonObject c = item.getAsJsonObject();
            Integer day = integer(c.get("day"));
            Integer start = integer(c.get("start"));
            Integer end = integer(c.get("end"));
            if (day == null || day < 1 || day > MAX_DAY) return null;
            if (start == null || start < 1 || start > MAX_PERIOD) return null;
            if (end == null || end < start || end > MAX_PERIOD) return null;
            String rawTitle = string(c.get("title"));
            if (rawTitle == null) return null;
            String title = sanitize(rawTitle, MAX_TITLE);
            if (title.isEmpty()) return null;
            String room;
            JsonElement r = c.get("room");
            if (r == null || r.isJsonNull()) {
                room = "";
            } else {
                String rawRoom = string(r);
                if (rawRoom == null) return null;
                room = sanitize(rawRoom, MAX_ROOM);
            }
            classes.add(new SharedClass(day, start, end, title, room));
        }
        return new SharedTimetable(name, ISO_SECONDS.format(created.truncatedTo(ChronoUnit.SECONDS)), classes);
    }

    /**
     * 制御文字と書式文字 (改行・RTL 上書き・ゼロ幅など) を落とし、前後の空白を切って max 文字 (コードポイント) までにする。
     */
    @NonNull
    public static String sanitize(@NonNull String raw, int max) {
        StringBuilder kept = new StringBuilder();
        raw.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type != Character.CONTROL && type != Character.FORMAT) kept.appendCodePoint(cp);
        });
        String trimmed = trim(kept.toString());
        int count = trimmed.codePointCount(0, trimmed.length());
        if (count > max) trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, max));
        return trim(trimmed);
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    @NonNull
    private static String trim(@NonNull String s) {
        int start = 0, end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isSpace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) break;
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    @Nullable
    private static String string(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    /** JSON の数が整数ならその値。true / false や 1.5、文字列は null。 */
    @Nullable
    private static Integer integer(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double d;
        try {
            d = p.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
        if (Double.isNaN(d) || d != Math.rint(d) || Math.abs(d) >= 1_000_000) return null;
        return (int) d;
    }

    @Nullable
    public static Instant parseInstant(@NonNull String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SharedTimetable)) return false;
        SharedTimetable t = (SharedTimetable) o;
        return name.equals(t.name) && createdAt.equals(t.createdAt) && classes.equals(t.classes);
    }

    @Override
    public int hashCode() { return Objects.hash(name, createdAt, classes); }
}
