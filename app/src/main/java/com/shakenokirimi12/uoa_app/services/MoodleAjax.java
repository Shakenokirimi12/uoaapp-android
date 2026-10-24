package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Moodle の service.php (ajax) の応答を読む。
 *
 * 失敗の応答は空の一覧にせず投げる。以前は課題の取得で {@code error: true} を「課題 0 件」として
 * 成功扱いにしていて、保存済みの課題と締切のリマインダーが空で上書きされていた (iOS は修正済み)。
 * 投げる例外のメッセージは Moodle の errorcode・例外のクラス・メッセージだけで、応答の本文・
 * sesskey・Cookie を含まない (Log に出すため)。
 */
final class MoodleAjax {
    private MoodleAjax() {}

    /** 読めなかった応答。メッセージはログに出してよい。 */
    static final class AjaxException extends IOException {
        AjaxException(String message) { super(message); }
    }

    /**
     * Moodle のエラーの応答を説明する。エラーでなければ null。
     *
     * 形は 3 通り (moodle/moodle MOODLE_405_STABLE):
     * 呼び出しごとの失敗 {@code {"error":true,"exception":{"message","errorcode",...}}}、
     * service.php 自体の失敗 {@code {"error":"<message>","errorcode":"..."}}、
     * Web サービス (REST) 形式 {@code {"exception":"moodle_exception","errorcode":"...","message":"..."}}。
     * debuginfo と backtrace は SQL やパスを含みうるので読まない。
     */
    @Nullable
    static String describeError(@Nullable Map<?, ?> item) {
        if (item == null) return null;
        Object exception = item.get("exception");
        if (exception instanceof Map) {
            Map<?, ?> e = (Map<?, ?>) exception;
            return describe(e.get("errorcode"), null, e.get("message"));
        }
        if (exception instanceof String) {
            return describe(item.get("errorcode"), (String) exception, item.get("message"));
        }
        if (item.get("errorcode") instanceof String) {
            return describe(item.get("errorcode"), null, item.get("error"));
        }
        if (Boolean.TRUE.equals(item.get("error"))) {
            return describe(null, null, null);
        }
        return null;
    }

    private static String describe(@Nullable Object errorcode, @Nullable String exceptionClass, @Nullable Object message) {
        String code = errorcode instanceof String ? (String) errorcode : "unknown";
        String text = message instanceof String ? (String) message : "-";
        if (text.length() > 200) text = text.substring(0, 200);
        return "errorcode=" + code + " exception=" + (exceptionClass != null ? exceptionClass : "-") + " message=" + text;
    }

    /** 1 つ目の呼び出しの data。JSON の配列でない・失敗・data が無いときは {@link AjaxException}。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstData(Gson gson, String response) throws AjaxException {
        Object parsed;
        try {
            parsed = gson.fromJson(response, Object.class);
        } catch (JsonParseException e) {
            parsed = null;
        }
        if (parsed instanceof Map) {
            String failure = describeError((Map<?, ?>) parsed);
            if (failure != null) throw new AjaxException(failure);
        }
        if (!(parsed instanceof List) || ((List<?>) parsed).isEmpty()) {
            int length = response == null ? 0 : response.length();
            throw new AjaxException("not a JSON array of results (" + length + " chars)");
        }
        Object first = ((List<?>) parsed).get(0);
        if (!(first instanceof Map)) throw new AjaxException("result is not an object");
        String failure = describeError((Map<?, ?>) first);
        if (failure != null) throw new AjaxException(failure);
        Object data = ((Map<?, ?>) first).get("data");
        if (!(data instanceof Map)) throw new AjaxException("no data");
        return (Map<String, Object>) data;
    }
}
