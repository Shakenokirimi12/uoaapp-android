package com.shakenokirimi12.uoa_app.ui.more;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.shakenokirimi12.uoa_app.R;

public class MoreFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_more, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // iOS の MoreMenuView と同じく、killswitch で止めた機能は入口ごと隠す。
        // 一度きりの評価にせず購読するのは、この画面を開いたままフラグが変わったときに
        // 前面復帰の取り直しで入口が消えるようにするため。
        com.shakenokirimi12.uoa_app.services.AppConfigService flags =
                com.shakenokirimi12.uoa_app.services.AppConfigService.getInstance();
        // 下タブに出している機能はここに重ねて出さない (タブの表示設定、iOS と同じ)。
        com.shakenokirimi12.uoa_app.data.PreferenceManager prefs =
                com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance(requireContext());
        flags.config().observe(getViewLifecycleOwner(), config -> {
            boolean reviews = flags.isFeatureEnabled("review_enabled")
                    && !com.shakenokirimi12.uoa_app.ui.util.MainTabs.isInMainTabs(prefs, "reviews");
            view.findViewById(R.id.button_reviews).setVisibility(reviews ? View.VISIBLE : View.GONE);
            view.findViewById(R.id.button_grades).setVisibility(
                    com.shakenokirimi12.uoa_app.ui.util.MainTabs.isInMainTabs(prefs, "grades") ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.button_gakushoku).setVisibility(
                    com.shakenokirimi12.uoa_app.ui.util.MainTabs.isInMainTabs(prefs, "gakushoku") ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.button_facilities).setVisibility(
                    com.shakenokirimi12.uoa_app.ui.util.MainTabs.isInMainTabs(prefs, "facilities") ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.button_campus_map).setVisibility(
                    flags.isFeatureEnabled("campus_map_enabled") ? View.VISIBLE : View.GONE);
            hideOrphanDividers((ViewGroup) view.findViewById(R.id.button_grades).getParent());
        });

        view.findViewById(R.id.button_reviews).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_reviews));
        view.findViewById(R.id.button_grades).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_grades));
        view.findViewById(R.id.button_gakushoku).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.navigation_gakushoku));
        view.findViewById(R.id.button_facilities).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_facilities));
        view.findViewById(R.id.button_campus_map).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_campus_map));
        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_notifications));
        view.findViewById(R.id.button_settings).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_settings));
    }

    /**
     * カード内の区切り線は「見えている行と行の間」にだけ出す。行を隠すと隣の線が浮くので、
     * 行ごとに線の id を対応付けるのではなく、並びを見て決める。
     */
    private static void hideOrphanDividers(ViewGroup card) {
        View lastVisibleRow = null;
        View pendingDivider = null;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof com.google.android.material.divider.MaterialDivider) {
                child.setVisibility(View.GONE);
                if (lastVisibleRow != null) pendingDivider = child;
                continue;
            }
            if (child.getVisibility() != View.VISIBLE) continue;
            if (lastVisibleRow != null && pendingDivider != null) pendingDivider.setVisibility(View.VISIBLE);
            lastVisibleRow = child;
            pendingDivider = null;
        }
    }
}
