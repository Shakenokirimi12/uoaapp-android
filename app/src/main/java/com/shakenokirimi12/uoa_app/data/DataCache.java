package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenuItem;
import com.shakenokirimi12.uoa_app.data.models.Grade;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DataCache {
    private static final String PREF_NAME = "uoa_data_cache";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_ASSIGNMENTS = "assignments";
    private static final String KEY_COURSES = "courses";
    private static final String KEY_GRADES = "grades";
    private static final String KEY_MENU = "menu";
    // "<dataset>_fetched_at": epoch ms of the last successful save. Lives in the same prefs so
    // clearAll() (logout) drops it together with the data.
    private static final String FETCHED_AT_SUFFIX = "_fetched_at";

    /** Datasets that carry a "fetched at" timestamp. */
    public enum Dataset {
        EVENTS(KEY_EVENTS), ASSIGNMENTS(KEY_ASSIGNMENTS), COURSES(KEY_COURSES),
        GRADES(KEY_GRADES), MENU(KEY_MENU);

        final String key;
        Dataset(String key) { this.key = key; }
    }

    /** CampusSquare / Moodle data: automatic (non pull-to-refresh) loads reuse the cache within this age. */
    public static final long DEFAULT_MAX_AGE_MS = 15 * 60_000L;
    /** The cafeteria menu changes at most daily, so a longer window is fine. */
    public static final long MENU_MAX_AGE_MS = 60 * 60_000L;

    private static DataCache instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private DataCache(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").create();
    }

    public static synchronized DataCache getInstance(Context context) {
        if (instance == null) {
            instance = new DataCache(context);
        }
        return instance;
    }

    public void saveEvents(List<CalendarEvent> events) {
        prefs.edit()
                .putString(KEY_EVENTS, gson.toJson(events))
                .putLong(KEY_EVENTS + FETCHED_AT_SUFFIX, System.currentTimeMillis())
                .apply();
    }

    public List<CalendarEvent> loadEvents() {
        return loadList(KEY_EVENTS, new TypeToken<List<CalendarEvent>>() {}.getType());
    }

    public void saveAssignments(List<Assignment> assignments) {
        prefs.edit()
                .putString(KEY_ASSIGNMENTS, gson.toJson(assignments))
                .putLong(KEY_ASSIGNMENTS + FETCHED_AT_SUFFIX, System.currentTimeMillis())
                .apply();
    }

    public List<Assignment> loadAssignments() {
        return loadList(KEY_ASSIGNMENTS, new TypeToken<List<Assignment>>() {}.getType());
    }

    public void saveCourses(List<MoodleCourse> courses) {
        prefs.edit()
                .putString(KEY_COURSES, gson.toJson(courses))
                .putLong(KEY_COURSES + FETCHED_AT_SUFFIX, System.currentTimeMillis())
                .apply();
    }

    public List<MoodleCourse> loadCourses() {
        return loadList(KEY_COURSES, new TypeToken<List<MoodleCourse>>() {}.getType());
    }

    public void saveGrades(List<Grade> grades) {
        prefs.edit()
                .putString(KEY_GRADES, gson.toJson(grades))
                .putLong(KEY_GRADES + FETCHED_AT_SUFFIX, System.currentTimeMillis())
                .apply();
    }

    public List<Grade> loadGrades() {
        return loadList(KEY_GRADES, new TypeToken<List<Grade>>() {}.getType());
    }

    public void saveMenu(List<GakushokuMenuItem> menu) {
        prefs.edit()
                .putString(KEY_MENU, gson.toJson(menu))
                .putLong(KEY_MENU + FETCHED_AT_SUFFIX, System.currentTimeMillis())
                .apply();
    }

    public List<GakushokuMenuItem> loadMenu() {
        return loadList(KEY_MENU, new TypeToken<List<GakushokuMenuItem>>() {}.getType());
    }

    /**
     * true if the dataset was saved within the last maxAgeMs. Never fetched (or cleared) reads as
     * stale, so the first load after login / logout always hits the network.
     */
    public boolean isFresh(Dataset dataset, long maxAgeMs) {
        long fetchedAt = prefs.getLong(dataset.key + FETCHED_AT_SUFFIX, 0L);
        if (fetchedAt <= 0) return false;
        long age = System.currentTimeMillis() - fetchedAt;
        // A clock set backwards makes age negative; treat that as stale rather than fresh forever.
        return age >= 0 && age < maxAgeMs;
    }

    /** Clears data and the fetched-at timestamps (both live in the same prefs). */
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    private <T> List<T> loadList(String key, Type type) {
        String json = prefs.getString(key, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<T> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
