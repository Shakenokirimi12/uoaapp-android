package com.shakenokirimi12.uoa_app.services.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayResponder;

import org.junit.Test;

import java.util.List;

/** 診断の記録 (Wana) の形。理由ごとにまとまる message、tag、時間、手順。サーバの応答の本文 (アドレスなど) は入れない。 */
public class OtpDiagnosticsTest {
    private static final OtpDiagnostics.Build BUILD = new OtpDiagnostics.Build("1.0.0", 3, false);

    @Test
    public void fetchEvent_carriesStepsButNoPrivateText() {
        ImapOtpFetcher.Progress progress = new ImapOtpFetcher.Progress();
        progress.note("a1 LOGIN *** ***");
        progress.note("a2 NO [NONEXISTENT]");
        JsonObject failed = OtpRelayResponder.fetchEvent(progress, OtpDiagnostics.CONTEXT_LOGIN, "7K3M-9QXA",
                "imap-error", "scan-stall:t2.1-f17.0", BUILD, 1_800_000_000_000L);
        assertEquals("otp-mail-fetch failure: scan-stall", failed.get("message").getAsString());
        assertEquals("error", failed.get("level").getAsString());
        assertEquals("uoa-app-android@1.0.0+3", failed.get("release").getAsString());
        JsonObject tags = failed.getAsJsonObject("tags");
        assertEquals("failure", tags.get("outcome").getAsString());
        assertEquals("imap-error", tags.get("reason").getAsString());
        assertEquals("scan-stall:t2.1-f17.0", tags.get("detail").getAsString());
        assertEquals("android-login", tags.get("context").getAsString());
        assertEquals("mail", tags.get("method").getAsString());
        assertEquals("android", tags.get("platform").getAsString());
        assertEquals("7K3M-9QXA", tags.get("support_id").getAsString());
        JsonArray crumbs = failed.getAsJsonObject("breadcrumbs").getAsJsonArray("values");
        assertEquals(2, crumbs.size());
        String text = crumbs.toString();
        assertTrue(text, text.contains("a2 NO [NONEXISTENT]"));
        assertFalse(text, text.contains("@"));

        JsonObject ok = OtpRelayResponder.fetchEvent(progress, OtpDiagnostics.CONTEXT_LOGIN, null, null, null, BUILD, 0);
        assertEquals("otp-mail-fetch success", ok.get("message").getAsString());
        assertEquals("info", ok.get("level").getAsString());
        assertFalse(ok.getAsJsonObject("tags").has("support_id"));
    }

    @Test
    public void relayEvents_includeTheFetch() {
        OtpRelayResponder.Attempt attempt = new OtpRelayResponder.Attempt();
        attempt.trail.set("method", "mail");
        attempt.trail.note("依頼を受けた");
        attempt.imap.note("a1 LOGIN *** ***");
        List<JsonObject> events = attempt.diagnosticEvents(null, "consent-required", BUILD, 0);
        // メールを読みに行かなかった: 中継 1 件だけ。
        assertEquals(1, events.size());
        assertEquals("otp-relay request failure: consent-required", events.get(0).get("message").getAsString());
        String crumbs = events.get(0).getAsJsonObject("breadcrumbs").toString();
        assertTrue(crumbs, crumbs.contains("\"category\":\"relay\""));
        assertTrue(crumbs, crumbs.contains("\"category\":\"imap\""));
        assertEquals("scan-stall", OtpDiagnostics.label("scan-stall:t2.1-l3.4"));
        assertEquals("mail-timeout", OtpDiagnostics.label("mail-timeout"));
    }
}
