package com.shakenokirimi12.uoa_app.services.usage;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 利用状況 (POST /push/api/usage) で数える機能の名前。
 *
 * サーバーは決まった名前しか受け取らない (uoa-app-push の src/usage.ts の USAGE_FEATURES)。iOS の
 * UsageFeature.swift とも同じ並びにする。名前を足す・変えるときは 3 か所を同じ変更で直す
 * (サーバーが知らない名前は黙って捨てられ、数えたつもりで何も残らない)。
 *
 * timetable_share (時間割の共有) と live_activity は iOS だけの機能で、Android では数えない。
 */
public final class UsageFeature {
    public static final String HOME = "home";
    public static final String CALENDAR = "calendar";
    public static final String COURSES = "courses";
    public static final String COURSE_DETAIL = "course_detail";
    public static final String ASSIGNMENT_DETAIL = "assignment_detail";
    public static final String GRADES = "grades";
    public static final String GAKUSHOKU = "gakushoku";
    public static final String FACILITY_USAGE = "facility_usage";
    public static final String SYLLABUS = "syllabus";
    public static final String SETTINGS = "settings";
    public static final String OTP_RELAY = "otp_relay";
    public static final String TIMETABLE_SHARE = "timetable_share";
    public static final String CLASS_LOCATION = "class_location";
    public static final String AUTO_ATTENDANCE = "auto_attendance";
    public static final String WIDGET_REFRESH = "widget_refresh";
    public static final String LIVE_ACTIVITY = "live_activity";

    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            HOME, CALENDAR, COURSES, COURSE_DETAIL, ASSIGNMENT_DETAIL, GRADES, GAKUSHOKU, FACILITY_USAGE,
            SYLLABUS, SETTINGS, OTP_RELAY, TIMETABLE_SHARE, CLASS_LOCATION, AUTO_ATTENDANCE, WIDGET_REFRESH,
            LIVE_ACTIVITY)));

    private UsageFeature() {}
}
