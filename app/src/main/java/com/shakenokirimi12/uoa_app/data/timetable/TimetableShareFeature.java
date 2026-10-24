package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.Nullable;

/**
 * 時間割共有を出すか。リモートのフラグ {@code timetable_share_qr_enabled} (既定 false) で決める。
 * Android には近くの端末と見せ合う従来の版が無いので、false の間は入口ごと隠す (iOS は従来の版を出す)。
 * 開いている交換・受け取りの画面は、途中でフラグが変わっても最後まで動かす。
 */
public final class TimetableShareFeature {
    public static final String FLAG_KEY = "timetable_share_qr_enabled";

    private TimetableShareFeature() {}

    /** "true" のときだけ出す (AppConfigService.isFlagEnabled と同じ読み方)。未設定・未取得なら出さない。 */
    public static boolean isEnabled(@Nullable String flagValue) {
        return "true".equals(flagValue);
    }
}
