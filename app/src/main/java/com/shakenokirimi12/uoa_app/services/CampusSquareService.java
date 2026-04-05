package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.FacilityUsage;
import com.shakenokirimi12.uoa_app.data.models.Grade;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CampusSquareService {
    private static final String BASE_URL = "https://csweb.u-aizu.ac.jp/campusweb";
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String sessionId = "";

    public void login(String username, String password, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                sessionId = doLogin(username, password);
                postSuccess(callback, true);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private String doLogin(String username, String password) throws Exception {
        OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();

        // Step 1: GET portal page for rwfHash + initial JSESSIONID
        Request getPortal = new Request.Builder()
                .url(BASE_URL + "/campusportal.do?locale=ja_JP")
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();

        String portalHtml;
        String initialSid;
        try (Response resp = noRedirect.newCall(getPortal).execute()) {
            // Follow redirects manually for initial page
            Response finalResp = followRedirectsManually(resp);
            portalHtml = finalResp.body().string();
            initialSid = extractSessionId(finalResp);
            finalResp.close();
        }

        String rwfHash = extractMatch(portalHtml, "'rwfHash'\\s*:\\s*'([a-f0-9]+)'");
        if (rwfHash == null || initialSid == null || initialSid.isEmpty()) {
            throw new Exception("ポータルページの読み込みに失敗しました");
        }

        // Step 2: POST login
        String body = "wfId=nwf_PTW0000002_login" +
                "&userName=" + urlEncode(username.trim()) +
                "&password=" + urlEncode(password.trim()) +
                "&locale=ja_JP&undefined=&action=rwf&tabId=home&page=&rwfHash=" + rwfHash;

        Request postLogin = new Request.Builder()
                .url(BASE_URL + "/campusportal.do")
                .header("User-Agent", NetworkClient.getUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "JSESSIONID=" + initialSid)
                .header("Referer", BASE_URL + "/campusportal.do?locale=ja_JP")
                .header("Origin", "https://csweb.u-aizu.ac.jp")
                .post(RequestBody.create(body, FORM))
                .build();

        String authSid;
        String locationHeader;
        try (Response resp = noRedirect.newCall(postLogin).execute()) {
            String newSid = extractSessionId(resp);
            authSid = (newSid != null && !newSid.isEmpty()) ? newSid : initialSid;
            locationHeader = resp.header("Location");
        }

        // Step 3: Follow redirect if present
        if (locationHeader != null && !locationHeader.isEmpty()) {
            String redirectUrl = resolveUrl(locationHeader);
            Request followRedirect = new Request.Builder()
                    .url(redirectUrl)
                    .header("User-Agent", NetworkClient.getUserAgent())
                    .header("Cookie", "JSESSIONID=" + authSid)
                    .header("Referer", BASE_URL + "/campusportal.do")
                    .build();
            try (Response resp = NetworkClient.getNoCookieClient().newCall(followRedirect).execute()) {
                resp.body().string();
            }
        }

        // Step 4: Verify login
        Request verifyReq = new Request.Builder()
                .url(BASE_URL + "/campusportal.do?page=main")
                .header("User-Agent", NetworkClient.getUserAgent())
                .header("Cookie", "JSESSIONID=" + authSid)
                .header("Referer", BASE_URL + "/campusportal.do")
                .build();

        try (Response resp = NetworkClient.getNoCookieClient().newCall(verifyReq).execute()) {
            String verifyHtml = resp.body().string();
            if (!verifyHtml.contains("ログアウト") && !verifyHtml.contains("Logout")) {
                throw new Exception("CampusSquare ログインに失敗しました");
            }
        }

        return authSid;
    }

    public void fetchGrades(String username, String password, ServiceCallback<List<Grade>> callback) {
        executor.execute(() -> {
            try {
                String sid = doLogin(username, password);

                // Navigate to grades tab
                Request tabReq = new Request.Builder()
                        .url(BASE_URL + "/campusportal.do?page=main&tabId=si")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", "JSESSIONID=" + sid)
                        .header("Referer", BASE_URL + "/campusportal.do?page=main")
                        .build();
                try (Response r = NetworkClient.getNoCookieClient().newCall(tabReq).execute()) {
                    r.body().string();
                }
                Thread.sleep(300);

                // Start grade flow
                OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();
                Request flowReq = new Request.Builder()
                        .url(BASE_URL + "/campussquare.do?_flowId=SIW0001200-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", "JSESSIONID=" + sid)
                        .header("sec-fetch-dest", "iframe")
                        .header("Referer", BASE_URL + "/campusportal.do?page=main&tabId=si")
                        .build();

                String flowHtml;
                String flowKey = null;
                try (Response resp = noRedirect.newCall(flowReq).execute()) {
                    String location = resp.header("Location");
                    flowKey = extractFlowKey(location);
                    if (flowKey != null && location != null) {
                        String redirectUrl = resolveUrl(location);
                        Request follow = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", NetworkClient.getUserAgent())
                                .header("Cookie", "JSESSIONID=" + sid)
                                .build();
                        try (Response r2 = NetworkClient.getNoCookieClient().newCall(follow).execute()) {
                            flowHtml = r2.body().string();
                        }
                    } else {
                        flowHtml = resp.body().string();
                    }
                }

                if (flowKey == null) {
                    flowKey = extractMatch(flowHtml, "_flowExecutionKey\"\\s*value=\"([a-zA-Z0-9_-]+)\"");
                }

                if (flowKey == null) {
                    postError(callback, "成績ページのフローキー取得に失敗しました");
                    return;
                }

                // POST to display grades
                String postBody = "_flowExecutionKey=" + flowKey + "&_eventId=display";
                Request gradePost = new Request.Builder()
                        .url(BASE_URL + "/campussquare.do")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", "JSESSIONID=" + sid)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Referer", BASE_URL + "/campussquare.do?_flowId=SIW0001200-flow&_flowExecutionKey=" + flowKey)
                        .post(RequestBody.create(postBody, FORM))
                        .build();

                String gradesHtml;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(gradePost).execute()) {
                    gradesHtml = resp.body().string();
                }

                List<Grade> grades = parseGrades(gradesHtml);
                postSuccess(callback, grades);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void fetchCalendarEvents(String username, String password,
                                    ServiceCallback<List<CalendarEvent>> callback) {
        executor.execute(() -> {
            try {
                String sid = doLogin(username, password);

                // Navigate to calendar tab
                Request tabReq = new Request.Builder()
                        .url(BASE_URL + "/campusportal.do?page=main&tabId=po")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", "JSESSIONID=" + sid)
                        .header("Referer", BASE_URL + "/campusportal.do?page=main")
                        .build();
                try (Response r = NetworkClient.getNoCookieClient().newCall(tabReq).execute()) {
                    r.body().string();
                }
                Thread.sleep(300);

                // Get calendar URL
                Request calReq = new Request.Builder()
                        .url(BASE_URL + "/campussquare.do?_flowId=POW2401000-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", "JSESSIONID=" + sid)
                        .header("sec-fetch-dest", "iframe")
                        .header("Referer", BASE_URL + "/campusportal.do?page=main&tabId=po")
                        .build();

                String calHtml;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(calReq).execute()) {
                    calHtml = resp.body().string();
                }

                String icsUrl = extractMatch(calHtml, "id=\"calendarNm\"[^>]*value=\"([^\"]+)\"");
                if (icsUrl == null || icsUrl.isEmpty()) {
                    postError(callback, "カレンダーURLの取得に失敗しました");
                    return;
                }

                // Fetch ICS file
                Request icsReq = new Request.Builder()
                        .url(icsUrl)
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String icsContent;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(icsReq).execute()) {
                    icsContent = resp.body().string();
                }

                List<CalendarEvent> events = parseICS(icsContent);
                postSuccess(callback, events);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private List<Grade> parseGrades(String html) {
        List<Grade> grades = new ArrayList<>();
        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);

        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            Matcher cellMatcher = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(row);
            List<String> cells = new ArrayList<>();
            while (cellMatcher.find()) {
                cells.add(stripTags(cellMatcher.group(1)).trim());
            }
            if (cells.size() >= 8) {
                String subject = cells.get(4);
                String credits = cells.get(5);
                String score = cells.get(6);
                String gradeStr = cells.get(7);
                if (!subject.isEmpty() && (!score.isEmpty() || !gradeStr.isEmpty())) {
                    Grade g = new Grade();
                    g.setCourseName(subject);
                    g.setCredits(credits);
                    g.setScore(score);
                    g.setGrade(gradeStr.isEmpty() ? "履修中" : gradeStr);
                    grades.add(g);
                }
            }
        }
        return grades;
    }

    private List<CalendarEvent> parseICS(String ics) {
        List<CalendarEvent> events = new ArrayList<>();
        String[] blocks = ics.split("BEGIN:VEVENT");

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i].split("END:VEVENT")[0];
            String summary = extractIcsField(block, "SUMMARY");
            String location = extractIcsField(block, "LOCATION");
            String dtStartRaw = extractIcsField(block, "DTSTART");
            String dtEndRaw = extractIcsField(block, "DTEND");

            if (summary == null || dtStartRaw == null || dtEndRaw == null) continue;

            summary = summary.replace("\\n", "\n").replace("\\,", ",");
            if (location != null) location = location.replace("\\n", "\n").replace("\\,", ",");

            Date dtStart = parseIcsDate(dtStartRaw);
            Date dtEnd = parseIcsDate(dtEndRaw);

            if (dtStart != null && dtEnd != null) {
                events.add(new CalendarEvent(summary, location, dtStart, dtEnd));
            }
        }
        return events;
    }

    private String extractIcsField(String block, String field) {
        Pattern p = Pattern.compile("^" + field + "[;:](.*)$", Pattern.MULTILINE);
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private Date parseIcsDate(String raw) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);

        if (raw.contains("TZID=Asia/Tokyo:")) {
            String clean = raw.replaceAll("TZID=Asia/Tokyo:", "");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            try { return sdf.parse(clean); } catch (Exception e) { return null; }
        } else if (raw.endsWith("Z")) {
            String clean = raw.substring(0, raw.length() - 1);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            try { return sdf.parse(clean); } catch (Exception e) { return null; }
        } else {
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            try { return sdf.parse(raw); } catch (Exception e) { return null; }
        }
    }

    private Response followRedirectsManually(Response resp) throws IOException {
        while (resp.isRedirect()) {
            String location = resp.header("Location");
            if (location == null) break;
            String newSid = extractSessionId(resp);
            String url = resolveUrl(location);
            resp.close();

            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.getUserAgent());
            if (newSid != null && !newSid.isEmpty()) {
                builder.header("Cookie", "JSESSIONID=" + newSid);
            }
            resp = NetworkClient.getNoRedirectClient().newCall(builder.build()).execute();
        }
        return resp;
    }

    private static String extractSessionId(Response resp) {
        String setCookie = resp.header("Set-Cookie");
        if (setCookie == null) return null;
        Matcher m = Pattern.compile("JSESSIONID=([A-Z0-9]+)").matcher(setCookie);
        return m.find() ? m.group(1) : null;
    }

    private static String extractFlowKey(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("_flowExecutionKey=([a-zA-Z0-9_-]+)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String resolveUrl(String location) {
        if (location.startsWith("http")) return location;
        if (location.startsWith("/")) return "https://csweb.u-aizu.ac.jp" + location;
        return BASE_URL + "/" + location;
    }

    private static String extractMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public void fetchFacilityUsage(String dateStr, ServiceCallback<List<FacilityUsage>> callback) {
        executor.execute(() -> {
            try {
                OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();
                OkHttpClient client = NetworkClient.getNoCookieClient();

                // GET initial flow page (no login required)
                Request flowReq = new Request.Builder()
                        .url(BASE_URL + "/campussquare.do?_flowId=KHW0001310-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String html;
                String sid = null;
                try (Response resp = noRedirect.newCall(flowReq).execute()) {
                    String newSid = extractSessionId(resp);
                    if (newSid != null) sid = newSid;
                    String location = resp.header("Location");
                    if (location != null) {
                        String redirectUrl = resolveUrl(location);
                        Request.Builder rb = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", NetworkClient.getUserAgent());
                        if (sid != null) rb.header("Cookie", "JSESSIONID=" + sid);
                        try (Response r2 = noRedirect.newCall(rb.build()).execute()) {
                            String ns = extractSessionId(r2);
                            if (ns != null) sid = ns;
                            String loc2 = r2.header("Location");
                            if (loc2 != null) {
                                Request.Builder rb2 = new Request.Builder()
                                        .url(resolveUrl(loc2))
                                        .header("User-Agent", NetworkClient.getUserAgent());
                                if (sid != null) rb2.header("Cookie", "JSESSIONID=" + sid);
                                try (Response r3 = client.newCall(rb2.build()).execute()) {
                                    html = r3.body().string();
                                }
                            } else {
                                html = r2.body().string();
                            }
                        }
                    } else {
                        html = resp.body().string();
                    }
                }

                // If date is specified, navigate to that date
                if (dateStr != null && !dateStr.isEmpty()) {
                    String flowKey = extractMatch(html,
                            "_flowExecutionKey\"\\s*value=\"([^\"]+)\"");
                    if (flowKey == null) {
                        flowKey = extractMatch(html, "_flowExecutionKey=([a-zA-Z0-9_-]+)");
                    }
                    if (flowKey != null && sid != null) {
                        String dateUrl = BASE_URL + "/campussquare.do?_flowExecutionKey="
                                + flowKey + "&_eventId=show&displayDate=" + dateStr;
                        Request dateReq = new Request.Builder()
                                .url(dateUrl)
                                .header("User-Agent", NetworkClient.getUserAgent())
                                .header("Cookie", "JSESSIONID=" + sid)
                                .build();
                        try (Response resp = client.newCall(dateReq).execute()) {
                            html = resp.body().string();
                        }
                    }
                }

                List<FacilityUsage> facilities = parseFacilityUsage(html);
                postSuccess(callback, facilities);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private List<FacilityUsage> parseFacilityUsage(String html) {
        List<FacilityUsage> facilities = new ArrayList<>();
        Pattern tdPattern = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL);
        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);

        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            if (!row.contains("kyuko-shi-shisetsunm")) continue;

            Matcher tdMatcher = tdPattern.matcher(row);
            String facilityName = "";
            int currentSlot = 0;
            int availableStart = 0; // 6:00 = slot 0

            List<FacilityUsage.ScheduleItem> schedule = new ArrayList<>();

            while (tdMatcher.find()) {
                String attrs = tdMatcher.group(1);
                String inner = tdMatcher.group(2).trim()
                        .replaceAll("\n", "")
                        .replaceAll("<br\\s*/?>", "");

                if (attrs.contains("kyuko-shi-shisetsunm")) {
                    if (attrs.contains("nowrap")) {
                        facilityName = stripTags(inner).trim();
                    }
                    // rowspan category cells (講義室, 演習室) - skip
                    continue;
                }

                if (currentSlot >= 108) continue;

                Matcher csMatcher = Pattern.compile("colspan=[\"']?(\\d+)[\"']?").matcher(attrs);
                int colspan = csMatcher.find() ? Integer.parseInt(csMatcher.group(1)) : 1;
                int durationMins = colspan * 10;
                int slotStartMins = 360 + currentSlot * 10;

                String cleanedInner = stripTags(inner).trim();
                boolean isBooked = !cleanedInner.isEmpty();

                if (isBooked) {
                    int availStartMins = 360 + availableStart * 10;
                    if (availStartMins < slotStartMins) {
                        schedule.add(new FacilityUsage.ScheduleItem(
                                availStartMins, slotStartMins, true, null));
                    }
                    schedule.add(new FacilityUsage.ScheduleItem(
                            slotStartMins, slotStartMins + durationMins, false, cleanedInner));
                    availableStart = currentSlot + colspan;
                }

                currentSlot += colspan;
            }

            // Trailing available block
            int endOfDayMins = 360 + 108 * 10; // 24:00
            int availStartMins = 360 + availableStart * 10;
            if (availStartMins < endOfDayMins) {
                schedule.add(new FacilityUsage.ScheduleItem(
                        availStartMins, endOfDayMins, true, null));
            }

            if (!facilityName.isEmpty()) {
                FacilityUsage facility = new FacilityUsage(facilityName);
                for (FacilityUsage.ScheduleItem item : schedule) {
                    facility.addScheduleItem(item);
                }
                facility.computeCurrentStatus();
                facilities.add(facility);
            }
        }
        return facilities;
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ");
    }

    private <T> void postSuccess(ServiceCallback<T> cb, T result) {
        mainHandler.post(() -> cb.onSuccess(result));
    }

    private <T> void postError(ServiceCallback<T> cb, String msg) {
        mainHandler.post(() -> cb.onError(msg != null ? msg : "不明なエラー"));
    }
}
