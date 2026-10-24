package com.shakenokirimi12.uoa_app.services.usage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 日ごと・機能ごとの回数 (端末の中だけの台帳)。Android に依存しないので JVM のテストで確かめる。
 *
 * 日は端末のローカル日付 (yyyy-MM-dd)。送るのは終わった日 (今日より前) の、新しい方から MAX_DAYS 日だけ。
 * 今日の分は回数が増え続けるので送らない (サーバーは (install, 日, 機能) の最初の 1 回だけを数える)。
 * MAX_DAYS 日より古い日はサーバーが受け取らないので、送らずに捨てる。
 */
public final class UsageLedger {
    /** 1 日・1 機能の上限 (サーバーも同じ値で切る)。 */
    public static final int MAX_COUNT = 1000;
    /** 送る日数の上限。今日の MAX_DAYS 日前まで。 */
    public static final int MAX_DAYS = 7;

    private final TreeMap<String, TreeMap<String, Integer>> days = new TreeMap<>();

    /** 保存した JSON から戻す。読めない部分は捨てる (回数が欠けるだけで、作った値は入れない)。 */
    @NonNull
    public static UsageLedger fromJson(@Nullable String json) {
        UsageLedger ledger = new UsageLedger();
        if (json == null || json.isEmpty()) return ledger;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return ledger;
            for (Map.Entry<String, JsonElement> day : root.getAsJsonObject().entrySet()) {
                if (parseDay(day.getKey()) == null || !day.getValue().isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> e : day.getValue().getAsJsonObject().entrySet()) {
                    if (!UsageFeature.ALL.contains(e.getKey()) || !e.getValue().isJsonPrimitive()) continue;
                    int count;
                    try {
                        count = e.getValue().getAsInt();
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    if (count <= 0) continue;
                    ledger.put(day.getKey(), e.getKey(), Math.min(count, MAX_COUNT));
                }
            }
        } catch (RuntimeException ignored) {
            // 壊れた保存値は空から数え直す。
        }
        return ledger;
    }

    @NonNull
    public String toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, TreeMap<String, Integer>> day : days.entrySet()) {
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> e : day.getValue().entrySet()) counts.addProperty(e.getKey(), e.getValue());
            root.add(day.getKey(), counts);
        }
        return root.toString();
    }

    /** 知らない名前は数えない (サーバーも受け取らない)。 */
    public void increment(@NonNull LocalDate day, @NonNull String feature) {
        if (!UsageFeature.ALL.contains(feature)) return;
        String key = day.toString();
        TreeMap<String, Integer> counts = days.get(key);
        int current = counts == null || counts.get(feature) == null ? 0 : counts.get(feature);
        put(key, feature, Math.min(current + 1, MAX_COUNT));
    }

    private void put(String day, String feature, int count) {
        TreeMap<String, Integer> counts = days.get(day);
        if (counts == null) {
            counts = new TreeMap<>();
            days.put(day, counts);
        }
        counts.put(feature, count);
    }

    /**
     * もう送れない日を捨てる。今日の MAX_DAYS 日より前の日と、時計が戻ったなどで明日より先になった日。
     * 変わったら true。
     */
    public boolean dropExpired(@NonNull LocalDate today) {
        LocalDate oldest = today.minusDays(MAX_DAYS);
        LocalDate newest = today.plusDays(1);
        List<String> drop = new ArrayList<>();
        for (String key : days.keySet()) {
            LocalDate d = parseDay(key);
            if (d == null || d.isBefore(oldest) || d.isAfter(newest)) drop.add(key);
        }
        for (String key : drop) days.remove(key);
        return !drop.isEmpty();
    }

    /** いま送る日 (今日より前で、今日の MAX_DAYS 日前以降)。古い順。 */
    @NonNull
    public List<String> sendableDays(@NonNull LocalDate today) {
        LocalDate oldest = today.minusDays(MAX_DAYS);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, Integer>> e : days.entrySet()) {
            LocalDate d = parseDay(e.getKey());
            if (d == null || !d.isBefore(today) || d.isBefore(oldest) || e.getValue().isEmpty()) continue;
            out.add(e.getKey());
        }
        return out;
    }

    @NonNull
    public Map<String, Integer> counts(@NonNull String day) {
        TreeMap<String, Integer> counts = days.get(day);
        return counts == null ? Collections.emptyMap() : Collections.unmodifiableMap(counts);
    }

    public void remove(@NonNull Collection<String> sentDays) {
        for (String day : sentDays) days.remove(day);
    }

    public boolean isEmpty() { return days.isEmpty(); }

    /**
     * POST /push/api/usage の本文。cohort が null なら期と区分はどちらも null (不明) で送る。
     * 学籍番号・端末 ID・時間割は入れない。
     */
    @NonNull
    public JsonObject buildBody(@NonNull String installId, @NonNull String appVersion,
                                @Nullable UsageCohort cohort, @NonNull List<String> sendDays) {
        JsonObject body = new JsonObject();
        body.addProperty("installId", installId);
        body.addProperty("os", "android");
        body.addProperty("appVersion", appVersion);
        if (cohort != null) {
            body.addProperty("cohort", cohort.cohort);
            body.addProperty("category", cohort.category);
        } else {
            body.add("cohort", com.google.gson.JsonNull.INSTANCE);
            body.add("category", com.google.gson.JsonNull.INSTANCE);
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String day : sendDays) {
            JsonObject entry = new JsonObject();
            entry.addProperty("day", day);
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> e : counts(day).entrySet()) counts.addProperty(e.getKey(), e.getValue());
            entry.add("counts", counts);
            arr.add(entry);
        }
        body.add("days", arr);
        return body;
    }

    @Nullable
    private static LocalDate parseDay(String key) {
        try {
            return LocalDate.parse(key);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
