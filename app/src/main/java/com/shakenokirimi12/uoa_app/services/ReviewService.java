package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.data.models.ReviewCourse;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ReviewService {

    private static final String BASE_URL = "https://uoa-app-reviews.ken20051205.workers.dev";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final OkHttpClient client = NetworkClient.getNoCookieClient();

    // Register device + student hash, get internal user ID
    public void register(String deviceId, String studentHash, ServiceCallback<String> callback) {
        executor.execute(() -> {
            try {
                String json = gson.toJson(Map.of(
                        "deviceId", deviceId,
                        "studentHash", studentHash
                ));
                Request request = new Request.Builder()
                        .url(BASE_URL + "/api/auth/register")
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, parseError(resp));
                        return;
                    }
                    Map<String, Object> result = gson.fromJson(resp.body().string(),
                            new TypeToken<Map<String, Object>>() {}.getType());
                    postSuccess(callback, (String) result.get("userId"));
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // Search courses
    public void searchCourses(String query, ServiceCallback<List<ReviewCourse>> callback) {
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

    // Get reviews for a course
    public void getReviews(String courseId, String userId, ServiceCallback<List<Review>> callback) {
        executor.execute(() -> {
            try {
                String url = BASE_URL + "/api/courses/" + courseId + "/reviews";
                if (userId != null && !userId.isEmpty()) {
                    url += "?userId=" + userId;
                }
                Request request = new Request.Builder().url(url).build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, "レビュー取得に失敗しました");
                        return;
                    }
                    Map<String, Object> result = gson.fromJson(resp.body().string(),
                            new TypeToken<Map<String, Object>>() {}.getType());
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
                            r.setInternalUserId(str(raw.get("internal_user_id")));
                            r.setReportCount(intVal(raw.get("report_count")));
                            r.setCreatedAt(str(raw.get("created_at")));
                            r.setReviewType(str(raw.get("review_type")));
                            reviews.add(r);
                        }
                    }
                    postSuccess(callback, reviews);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // Submit a review
    public void submitReview(String courseId, String courseName, String userId,
                             int rating, String title, String body, int enrollmentYear,
                             String reviewType, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String json = gson.toJson(Map.of(
                        "userId", userId,
                        "courseName", courseName,
                        "rating", rating,
                        "title", title != null ? title : "",
                        "body", body,
                        "enrollmentYear", enrollmentYear,
                        "reviewType", reviewType != null ? reviewType : "course"
                ));
                Request request = new Request.Builder()
                        .url(BASE_URL + "/api/courses/" + courseId + "/reviews")
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, parseError(resp));
                        return;
                    }
                    postSuccess(callback, true);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // Report a review
    public void reportReview(String reviewId, String userId, String reason,
                             ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String json = gson.toJson(Map.of(
                        "userId", userId,
                        "reason", reason
                ));
                Request request = new Request.Builder()
                        .url(BASE_URL + "/api/reviews/" + reviewId + "/report")
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, parseError(resp));
                        return;
                    }
                    postSuccess(callback, true);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // Block a user
    public void blockUser(String targetUserId, String userId, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String json = gson.toJson(Map.of("userId", userId));
                Request request = new Request.Builder()
                        .url(BASE_URL + "/api/users/" + targetUserId + "/block")
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, parseError(resp));
                        return;
                    }
                    postSuccess(callback, true);
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private String parseError(Response resp) {
        try {
            Map<String, Object> err = gson.fromJson(resp.body().string(),
                    new TypeToken<Map<String, Object>>() {}.getType());
            return str(err.get("error"));
        } catch (Exception e) {
            return "HTTP " + resp.code();
        }
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
