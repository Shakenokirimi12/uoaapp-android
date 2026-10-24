package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/** Moodle の ajax の失敗は空の一覧ではなく失敗にし、ログには errorcode だけを出す。 */
public class MoodleAjaxTest {
    private final Gson gson = new Gson();

    private static final String SERVICE_NOT_AVAILABLE =
            "[{\"error\":true,\"exception\":{\"message\":\"ウェブサービスを利用できません。\","
                    + "\"errorcode\":\"servicenotavailable\",\"link\":\"https://elms.u-aizu.ac.jp/\","
                    + "\"moreinfourl\":\"http://docs.moodle.org/405/ja/error/webservice/servicenotavailable\"}}]";

    private String failureOf(String response) {
        try {
            MoodleAjax.firstData(gson, response);
            fail("expected AjaxException for " + response);
            return null;
        } catch (MoodleAjax.AjaxException e) {
            return e.getMessage();
        }
    }

    @Test
    public void errorPayloadThrowsWithTheErrorcode() {
        String message = failureOf(SERVICE_NOT_AVAILABLE);
        assertTrue(message, message.contains("errorcode=servicenotavailable"));
        assertTrue(message, message.contains("exception=-"));
    }

    @Test
    public void serviceLevelAndRestStyleErrorsCarryTheErrorcode() {
        assertTrue(failureOf("{\"error\":\"このページは期限切れです。\",\"errorcode\":\"invalidsesskey\"}")
                .contains("errorcode=invalidsesskey"));
        String rest = failureOf("{\"exception\":\"moodle_exception\",\"errorcode\":\"invalidrecord\",\"message\":\"m\"}");
        assertTrue(rest, rest.contains("errorcode=invalidrecord"));
        assertTrue(rest, rest.contains("exception=moodle_exception"));
    }

    /** ログインページ (JSON でない) の本文はメッセージに入れない。 */
    @Test
    public void nonJsonResponsesThrowWithoutTheBody() {
        String message = failureOf("<html><script>M.cfg = {\"sesskey\":\"SECRET\"};</script></html>");
        assertFalse(message, message.contains("SECRET"));
        failureOf("");
        failureOf("[]");
        failureOf("[{\"error\":false}]");
    }

    @Test
    public void successReturnsTheFirstData() throws Exception {
        Map<String, Object> data = MoodleAjax.firstData(gson,
                "[{\"error\":false,\"data\":{\"events\":[{\"id\":7,\"name\":\"Report 1\",\"timesort\":1790000000}]}}]");
        assertEquals(1, ((List<?>) data.get("events")).size());
    }

    @Test
    public void successIsNotAnError() {
        assertNull(MoodleAjax.describeError(null));
        assertNull(MoodleAjax.describeError(Map.of("error", false, "data", Map.of())));
    }
}
