package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.data.models.ReviewBlock;
import com.shakenokirimi12.uoa_app.data.models.ReviewCourse;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 授業評価サーバー (uoa-app-reviews) のクライアント。iOS の ReviewService と同じ振る舞いにしている。
 *
 * 以前は userId そのものが資格情報で、しかもレビュー一覧に載っていたため誰でも他人の投稿を消せた。
 * 現在は登録時にサーバーが発行するトークンを Authorization: Bearer で送り、書き込みはすべてそれで本人確認する。
 */
public class ReviewService {

    private static final String BASE_URL = "https://uoa-app-reviews.shakenokirimi12.workers.dev";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final OkHttpClient client = NetworkClient.getNoCookieClient();

    private static final String TAG = "ReviewService";
    /** 認証トークンの取得 (claim/register) を全インスタンスで1本にまとめるためのロック。 */
    private static final Object SESSION_LOCK = new Object();

    // Search courses
    public void searchCourses(String query, ServiceCallback<List<ReviewCourse>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("review_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                String url = BASE_URL + "/api/courses";
                if (query != null && !query.isEmpty()) {
                    url += "?q=" + java.net.URLEncoder.encode(query, "UTF-8");
                }
                Request request = new Request.Builder().url(url).build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, "コース取得に失敗しました");
                        return;
                    }
                    String body = resp.body().string();
                    Map<String, Object> result = gson.fromJson(body,
                            new TypeToken<Map<String, Object>>() {}.getType());
                    List<Map<String, Object>> coursesRaw = (List<Map<String, Object>>) result.get("courses");
                    List<ReviewCourse> courses = new ArrayList<>();
                    if (coursesRaw != null) {
                        for (Map<String, Object> raw : coursesRaw) {
                            ReviewCourse rc = new ReviewCourse();
                            rc.setCourseId(str(raw.get("course_id")));
                            rc.setCourseName(str(raw.get("course_name")));
                            rc.setInstructor(str(raw.get("instructor")));
                            rc.setCredits(intVal(raw.get("credits")));
                            rc.setCategory(str(raw.get("category")));
                            rc.setSyllabusUrl(str(raw.get("syllabus_url")));
                            rc.setReviewCount(intVal(raw.get("review_count")));
                            rc.setAvgRating(doubleVal(raw.get("avg_rating")));
                            courses.add(rc);
                        }
                    }
                    postSuccess(callback, courses);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // Search instructors
    public void searchInstructors(String query, ServiceCallback<List<ReviewCourse>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("review_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                String url = BASE_URL + "/api/instructors";
                if (query != null && !query.isEmpty()) {
                    url += "?q=" + java.net.URLEncoder.encode(query, "UTF-8");
                }
                Request request = new Request.Builder().url(url).build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, "教員一覧の取得に失敗しました");
                        return;
                    }
                    String body = resp.body().string();
                    Map<String, Object> result = gson.fromJson(body,
                            new TypeToken<Map<String, Object>>() {}.getType());
                    List<Map<String, Object>> raw = (List<Map<String, Object>>) result.get("instructors");
                    List<ReviewCourse> instructors = new ArrayList<>();
                    if (raw != null) {
                        for (Map<String, Object> r : raw) {
                            ReviewCourse rc = new ReviewCourse();
                            String name = str(r.get("instructor_name"));
                            rc.setCourseId(name.replace(" ", "_"));
                            rc.setCourseName(name);
                            rc.setReviewCount(intVal(r.get("review_count")));
                            rc.setAvgRating(doubleVal(r.get("avg_rating")));
                            instructors.add(rc);
                        }
                    }
                    postSuccess(callback, instructors);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ── Session ──

    /**
     * 授業評価のアカウントを用意する (授業評価一覧を開いたとき用)。未登録なら登録し、旧バージョンで
     * 登録済み (トークン無し) ならトークンを引き継ぐ。
     * onError はサーバーが理由を返したとき (409/429/403 など) だけ呼ぶ。圏外などの通信エラーは
     * 開くたびに出さず、ログにだけ残す (iOS の ensureRegistered と同じ)。
     */
    public void prepareSession(ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                ensureSession(true);
                postSuccess(callback, true);
            } catch (ReviewError e) {
                if (e.status > 0) postError(callback, e.getMessage());
                else Log.w(TAG, "prepareSession: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "prepareSession failed", e);
            }
        });
    }

    /**
     * 有効な認証トークンを返す。無ければ取得する。バックグラウンドスレッドから呼ぶ。
     *
     * 画面ごとに ReviewService を作っているので、取得 (claim/register) は static なロックで1本にまとめる。
     * 一覧と詳細が同時に開くと二重登録になるため。後から来た呼び出しは先行分の完了を待ち、
     * 保存済みのトークンをそのまま使う。
     *
     * - userId はあるがトークンが無い (旧バージョンで登録した) 場合は、userId と学籍番号ハッシュで
     *   1回だけトークンを受け取る (/api/auth/claim)。
     * - claim が 404/409 (アカウントが消えた・既に誰かが受け取った) のときは新しいアカウントで登録し直す。
     *   その場合、古いアカウントの投稿はこの端末から編集・削除できなくなる。
     */
    private String ensureSession(boolean allowRegister) throws IOException {
        synchronized (SESSION_LOCK) {
            PreferenceManager prefs = PreferenceManager.getInstance();
            String token = prefs.getReviewToken();
            if (!token.isEmpty() && !prefs.getReviewUserId().isEmpty()) return token;
            return establishSession(prefs, allowRegister);
        }
    }

    private String establishSession(PreferenceManager prefs, boolean allowRegister) throws IOException {
        String username = prefs.getUsername();
        if (username.isEmpty()) throw new ReviewError(0, "学内システムへのログインが必要です");
        // サーバーの既存行と一致させるため、旧実装と同じく username をそのままハッシュする
        String studentHash = sha256(username);

        // トークンだけが残り userId が消えている場合は、トークンから userId を取り戻して同じアカウントを使い続ける
        // (iOS ではアプリの入れ直しで起きる。Android は両方とも暗号化 prefs にあるので通常は揃って消える)。
        String stored = prefs.getReviewToken();
        if (!stored.isEmpty() && prefs.getReviewUserId().isEmpty()) {
            try {
                Map<String, Object> json = jsonObject(perform("GET", "/api/auth/me", null, stored));
                Object userId = json.get("userId");
                if (userId instanceof String && !((String) userId).isEmpty()) {
                    ensureSameUser(prefs, username);
                    prefs.setReviewUserId((String) userId);
                    return stored;
                }
            } catch (ReviewError e) {
                if (e.status != 401) throw e;
                // トークンに対応するアカウントがもう無い。下で取り直す
                prefs.setReviewToken(null);
            }
        }

        String legacyUserId = prefs.getReviewUserId();
        if (!legacyUserId.isEmpty()) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("userId", legacyUserId);
                payload.put("studentHash", studentHash);
                payload.put("deviceId", prefs.getDeviceId());
                Map<String, Object> json = jsonObject(perform("POST", "/api/auth/claim", payload, null));
                String token = nonEmpty(json.get("token"));
                if (token == null) throw new ReviewError(0, "不正なレスポンス");
                ensureSameUser(prefs, username);
                saveToken(prefs, token);
                return token;
            } catch (ReviewError e) {
                if (e.status != 404 && e.status != 409) throw e;
                // 通常は下で新しいアカウントを作る
                if (!allowRegister) {
                    throw e.status == 409
                            ? new ReviewError(409, "このアカウントは別の端末で引き継がれているため、この端末からは操作できません")
                            : e;
                }
            }
        }
        if (!allowRegister) throw new ReviewError(0, "学内システムへのログインが必要です");

        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", prefs.getDeviceId());
        payload.put("studentHash", studentHash);
        payload.put("issueToken", true);
        Map<String, Object> json = jsonObject(perform("POST", "/api/auth/register", payload, null));
        String userId = nonEmpty(json.get("userId"));
        String token = nonEmpty(json.get("token"));
        if (userId == null || token == null) throw new ReviewError(0, "不正なレスポンス");
        ensureSameUser(prefs, username);
        saveToken(prefs, token);
        prefs.setReviewUserId(userId);
        return token;
    }

    /**
     * 通信している間に大学アカウントが切り替わっていたら、前のユーザーの学籍番号で得たアカウントを保存しない
     * (setUsername が消した直後に書き戻すと、新しいユーザーが前のユーザーとして操作できてしまう)。
     */
    private static void ensureSameUser(PreferenceManager prefs, String usernameUsed) throws ReviewError {
        if (!PreferenceManager.normalizeUsername(prefs.getUsername()).equals(PreferenceManager.normalizeUsername(usernameUsed))) {
            throw new ReviewError(0, "ログイン中のアカウントが変わったため、授業評価の準備を中断しました");
        }
    }

    private static void saveToken(PreferenceManager prefs, String token) throws ReviewError {
        if (!prefs.setReviewToken(token)) throw new ReviewError(0, "認証情報を端末に保存できませんでした");
    }

    // ── Reviews ──

    // Get reviews for a course
    public void getReviews(String courseId, ServiceCallback<List<Review>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("review_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                // 閲覧は認証なしでもできるが、トークンがあれば自分の投稿の判定とブロックの反映に使う。
                // 同意とガイドライン承諾の後なら、旧バージョンからの移行 (claim) もここで済ませる。
                // 同意前に学籍番号ハッシュを送らないよう、未同意なら保存済みのトークンだけを使う。
                PreferenceManager prefs = PreferenceManager.getInstance();
                String token;
                if (prefs.isReviewConsentGiven() && prefs.isReviewGuidelinesAccepted()) {
                    try {
                        token = ensureSession(true);
                    } catch (Exception e) {
                        Log.w(TAG, "getReviews: session unavailable", e);
                        token = null;
                    }
                } else {
                    token = prefs.getReviewToken();
                }

                String text = perform("GET", "/api/courses/" + seg(courseId) + "/reviews", null, token);
                Map<String, Object> result = jsonObject(text);
                List<Map<String, Object>> reviewsRaw = (List<Map<String, Object>>) result.get("reviews");
                List<Review> reviews = new ArrayList<>();
                if (reviewsRaw != null) {
                    for (Map<String, Object> raw : reviewsRaw) {
                        Review r = new Review();
                        r.setId(str(raw.get("id")));
                        r.setCourseId(str(raw.get("course_id")));
                        r.setCourseName(str(raw.get("course_name")));
                        r.setEnrollmentYear(intVal(raw.get("enrollment_year")));
                        r.setRating(intVal(raw.get("rating")));
                        r.setTitle(str(raw.get("title")));
                        r.setBody(str(raw.get("body")));
                        // internal_user_id は旧アプリ互換のためにレビュー ID が入っているだけなので読まない
                        r.setMine(Boolean.TRUE.equals(raw.get("is_mine")));
                        r.setReportCount(intVal(raw.get("report_count")));
                        r.setCreatedAt(str(raw.get("created_at")));
                        r.setReviewType(str(raw.get("review_type")));
                        r.setInstructor(str(raw.get("instructor")));
                        r.setStudentScore(str(raw.get("student_score")));
                        r.setStudentGrade(str(raw.get("student_grade")));
                        reviews.add(r);
                    }
                }
                postSuccess(callback, reviews);
            } catch (ReviewError e) {
                postError(callback, e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "getReviews failed", e);
                postError(callback, "レビュー取得に失敗しました");
            }
        });
    }

    // Submit a review
    public void submitReview(String courseId, String courseName,
                             int rating, String title, String body, int enrollmentYear,
                             String reviewType, String instructor, ServiceCallback<Boolean> callback) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("courseName", courseName);
        payload.put("rating", rating);
        payload.put("title", title != null ? title : "");
        payload.put("body", body);
        payload.put("enrollmentYear", enrollmentYear);
        payload.put("reviewType", reviewType != null ? reviewType : "course");
        if (instructor != null && !instructor.isEmpty()) payload.put("instructor", instructor);
        write("POST", "/api/courses/" + seg(courseId) + "/reviews", payload, callback);
    }

    /** 自分のレビューを編集する。サーバーは送らなかった欄を空で上書きするので、元の値を全部送り返す。 */
    public void editReview(String reviewId, int rating, String title, String body, int enrollmentYear,
                           String instructor, String studentScore, String studentGrade,
                           ServiceCallback<Boolean> callback) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rating", rating);
        payload.put("title", title != null ? title : "");
        payload.put("body", body);
        payload.put("enrollmentYear", enrollmentYear);
        payload.put("instructor", instructor != null ? instructor : "");
        payload.put("studentScore", studentScore != null ? studentScore : "");
        payload.put("studentGrade", studentGrade != null ? studentGrade : "");
        write("PUT", "/api/reviews/" + seg(reviewId), payload, callback);
    }

    public void deleteReview(String reviewId, ServiceCallback<Boolean> callback) {
        write("DELETE", "/api/reviews/" + seg(reviewId), null, callback);
    }

    // ── Report & Block ──

    public void reportReview(String reviewId, String reason, ServiceCallback<Boolean> callback) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", reason);
        write("POST", "/api/reviews/" + seg(reviewId) + "/report", payload, callback);
    }

    /** レビューの投稿者をブロックする。投稿者 ID は公開されないため、レビュー ID で指定する。 */
    public void blockAuthor(String reviewId, ServiceCallback<Boolean> callback) {
        write("POST", "/api/reviews/" + seg(reviewId) + "/block", null, callback);
    }

    // ブロック中のユーザー一覧
    public void fetchBlocks(ServiceCallback<List<ReviewBlock>> callback) {
        executor.execute(() -> {
            try {
                Map<String, Object> json = jsonObject(authorized("GET", "/api/blocks", null));
                List<ReviewBlock> blocks = new ArrayList<>();
                Object raw = json.get("blocks");
                if (raw instanceof List) {
                    for (Object o : (List<?>) raw) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) o;
                        String id = nonEmpty(m.get("id"));
                        if (id == null) continue;
                        blocks.add(new ReviewBlock(id, nonEmpty(m.get("created_at"))));
                    }
                }
                postSuccess(callback, blocks);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ブロック解除
    public void unblock(String blockId, ServiceCallback<Boolean> callback) {
        write("DELETE", "/api/blocks/" + seg(blockId), null, callback);
    }

    // ── Private ──

    /** 認証付きの書き込みを実行し、結果だけを返す。 */
    private void write(String method, String path, Map<String, Object> body, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                authorized(method, path, body);
                postSuccess(callback, true);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 認証付きリクエスト。
     *
     * 401 はトークンに対応するアカウントがサーバーに無い (退会済み等) ことを意味する。サーバーは一度発行した
     * トークンを差し替えないので、同じアカウントを取り戻せるのは「まだトークンが無い旧アカウント」の引き継ぎだけ。
     * それも駄目なら新規登録はせずにエラーを返す (ここで黙って作り直すと、利用者は自分の投稿の扱いが
     * 変わったことに気づけない)。
     */
    private String authorized(String method, String path, Map<String, Object> body) throws IOException {
        String token = ensureSession(true);
        try {
            return perform(method, path, body, token);
        } catch (ReviewError e) {
            if (e.status != 401) throw e;
            synchronized (SESSION_LOCK) {
                // 待っている間に別の呼び出しが取り直していたら、そのトークンは消さない
                PreferenceManager prefs = PreferenceManager.getInstance();
                if (token.equals(prefs.getReviewToken())) prefs.setReviewToken(null);
            }
            String fresh;
            try {
                fresh = ensureSession(false);
            } catch (ReviewError e2) {
                throw new ReviewError(401, "授業評価のアカウントを確認できませんでした(退会済みの可能性があります)。授業評価の画面を開き直してください。");
            }
            return perform(method, path, body, fresh);
        }
    }

    private String perform(String method, String path, Map<String, Object> body, String token) throws IOException {
        Request.Builder builder = new Request.Builder().url(BASE_URL + path);
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(gson.toJson(body), JSON_TYPE);
        } else if ("POST".equals(method) || "PUT".equals(method)) {
            // OkHttp は POST/PUT に本文を必須とする。サーバーは本文を読まない (block 等)。
            requestBody = RequestBody.create(new byte[0], (MediaType) null);
        }
        builder.method(method, requestBody);

        try (Response resp = client.newCall(builder.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new ReviewError(resp.code(), errorMessage(resp.code(), text));
            }
            return text;
        }
    }

    /** サーバーの {"error": "..."} をそのまま使う (409/429 などは理由が日本語で返る)。 */
    private String errorMessage(int status, String text) {
        try {
            Map<String, Object> err = gson.fromJson(text, new TypeToken<Map<String, Object>>() {}.getType());
            String msg = err != null ? nonEmpty(err.get("error")) : null;
            if (msg != null) return msg;
        } catch (Exception ignored) {
            // 本文が JSON でない (Cloudflare のエラーページ等) ときは下の文言にする
        }
        return status == 429
                ? "アクセスが集中しています。しばらく待ってからもう一度お試しください。"
                : "HTTP " + status;
    }

    private Map<String, Object> jsonObject(String text) throws ReviewError {
        try {
            Map<String, Object> json = gson.fromJson(text, new TypeToken<Map<String, Object>>() {}.getType());
            if (json != null) return json;
        } catch (Exception ignored) {
            // 下で不正なレスポンスとして扱う
        }
        throw new ReviewError(0, "不正なレスポンス");
    }

    private static String sha256(String input) throws ReviewError {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 以前は失敗時に入力 (学籍番号そのもの) を返していた。平文をサーバーへ送らないよう止める。
            throw new ReviewError(0, "学籍番号のハッシュを計算できませんでした");
        }
    }

    /**
     * 授業評価サーバーとのやり取りの失敗。status はサーバーが返した HTTP ステータス、
     * 0 は端末側の失敗 (未ログイン・不正なレスポンス・保存失敗)。メッセージはそのまま利用者に見せる。
     */
    private static class ReviewError extends IOException {
        final int status;

        ReviewError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** パスの1区画として埋め込む。教員キー (instructor_名前) などに / ? # や空白が入っても URL を壊さない。 */
    private static String seg(String value) {
        try {
            return java.net.URLEncoder.encode(value != null ? value : "", "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 は必ずある
        }
    }

    private static String nonEmpty(Object o) {
        if (!(o instanceof String)) return null;
        String s = (String) o;
        return s.isEmpty() ? null : s;
    }

    private static String str(Object o) { return o != null ? String.valueOf(o) : ""; }
    private static int intVal(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return 0;
    }
    private static double doubleVal(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    private <T> void postSuccess(ServiceCallback<T> cb, T result) {
        mainHandler.post(() -> cb.onSuccess(result));
    }
    private <T> void postError(ServiceCallback<T> cb, String msg) {
        mainHandler.post(() -> cb.onError(msg != null ? msg : "不明なエラー"));
    }
}
