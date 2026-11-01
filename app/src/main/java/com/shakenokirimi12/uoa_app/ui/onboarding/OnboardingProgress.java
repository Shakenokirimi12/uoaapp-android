package com.shakenokirimi12.uoa_app.ui.onboarding;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 初期設定のどの段を見たか (SPEC.md 9.5)。アップデートで段が増えたら、初期設定を終えた人には見ていない段だけを
 * 次の起動で出す。段は出した時点で見たことにする (閉じても飛ばしても、次の起動でまた出さない)。iOS の
 * OnboardingProgress と同じ決まり。
 */
public final class OnboardingProgress {
    // 保存する値なので、名前を変えない。PC との連携の 4 ページは 1 つの段として数える。
    public static final String DISCLAIMER = "disclaimer";
    public static final String ACCOUNT = "account";
    public static final String PC_RELAY = "pcRelay";

    /**
     * 初期設定を終えた人に、あとからそれだけで出せる段。同意とログインは全体の流れの中でしか出さない。
     * 段を足すときは、単独で出せるならここにも足し、OnboardingActivity の増えた段だけの表示にも足す。
     */
    static final Set<String> CATCH_UP_STEPS = Collections.singleton(PC_RELAY);
    /** 記録を始める前に初期設定を終えた人が見ていた段。PC との連携の段はあとから足した。 */
    static final Set<String> SEEN_BEFORE_TRACKING =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(DISCLAIMER, ACCOUNT)));

    private OnboardingProgress() {}

    /** 初期設定を終えた人に、あとから出す段。終えていない人は全部の段を通るので空。 */
    @NonNull
    static Set<String> newSteps(boolean onboardingDone, @Nullable Set<String> seen) {
        Set<String> steps = new HashSet<>();
        if (!onboardingDone) return steps;
        steps.addAll(CATCH_UP_STEPS);
        steps.removeAll(seen != null ? seen : SEEN_BEFORE_TRACKING);
        return steps;
    }

    /** 見た段を足した記録。記録が無い (記録を始める前に終えた) 人は、そのとき見ていた段に足す。 */
    @NonNull
    static Set<String> withSeen(@Nullable Set<String> seen, @NonNull Collection<String> steps) {
        Set<String> merged = new HashSet<>(seen != null ? seen : SEEN_BEFORE_TRACKING);
        merged.addAll(steps);
        return merged;
    }

    /** 起動時に、増えた段を出すか。連携済みの人には PC との連携の案内は要らないので、見たことにして出さない。 */
    @NonNull
    public static Set<String> newSteps(@NonNull PreferenceManager prefs) {
        if (prefs.isOnboardingDone() && !OtpRelayStore.all().isEmpty()) markSeen(prefs, PC_RELAY);
        return newSteps(prefs.isOnboardingDone(), prefs.getOnboardingSeenSteps());
    }

    public static void markSeen(@NonNull PreferenceManager prefs, @NonNull String... steps) {
        prefs.setOnboardingSeenSteps(withSeen(prefs.getOnboardingSeenSteps(), Arrays.asList(steps)));
    }
}
