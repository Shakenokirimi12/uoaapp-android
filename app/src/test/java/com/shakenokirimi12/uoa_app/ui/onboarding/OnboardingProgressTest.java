package com.shakenokirimi12.uoa_app.ui.onboarding;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class OnboardingProgressTest {
    private static Set<String> set(String... steps) {
        return new HashSet<>(Arrays.asList(steps));
    }

    /** 記録を始める前に初期設定を終えた人には、あとから足した段 (PC との連携) だけを出す。 */
    @Test
    public void updaterWithoutRecordSeesOnlyTheAddedSteps() {
        assertEquals(set(OnboardingProgress.PC_RELAY), OnboardingProgress.newSteps(true, null));
    }

    /** 初期設定を終えていない人は全部の段を通るので、増えた段としては出さない。 */
    @Test
    public void notOnboardedHasNoAddedSteps() {
        assertEquals(Collections.emptySet(), OnboardingProgress.newSteps(false, null));
    }

    /** 見た段は次から出さない。記録が無い人に足しても、それまで見ていた段は見たままにする。 */
    @Test
    public void markingSeenKeepsStepsSeenBeforeTracking() {
        Set<String> seen = OnboardingProgress.withSeen(null, Collections.singleton(OnboardingProgress.PC_RELAY));
        assertEquals(set(OnboardingProgress.DISCLAIMER, OnboardingProgress.ACCOUNT, OnboardingProgress.PC_RELAY), seen);
        assertEquals(Collections.emptySet(), OnboardingProgress.newSteps(true, seen));
    }
}
