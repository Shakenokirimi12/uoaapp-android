package com.shakenokirimi12.uoa_app.ui.more;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.ui.util.MainTabs;

import java.util.ArrayList;
import java.util.List;

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
        // 下タブに出していない画面は、すべてここに並べる (タブの表示設定、iOS と同じ)。
        // 一覧を手で持つと、タブから外した画面 (カレンダー・履修など) がどこからも開けなくなる。
        // タブの表示設定はこの画面から設定を経由して変えるので、戻ると onViewCreated から作り直される。
        List<String> moreKeys = MainTabs.moreTabKeys(
                com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance(requireContext()));
        ViewGroup features = view.findViewById(R.id.layout_more_features);
        List<View> tabRows = addTabRows(features, moreKeys);
        flags.config().observe(getViewLifecycleOwner(), config -> {
            for (int i = 0; i < moreKeys.size(); i++) {
                boolean visible = !"reviews".equals(moreKeys.get(i))
                        || flags.isFeatureEnabled("review_enabled");
                tabRows.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
            }
            view.findViewById(R.id.button_campus_map).setVisibility(
                    flags.isFeatureEnabled("campus_map_enabled") ? View.VISIBLE : View.GONE);
            hideOrphanDividers(features);
        });

        view.findViewById(R.id.button_campus_map).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_campus_map));
        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_notifications));
        view.findViewById(R.id.button_settings).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_settings));
    }

    /** 機能のカードの先頭に、渡したキーの順で行を差し込む。戻り値はキーと同じ順の行。 */
    private List<View> addTabRows(ViewGroup features, List<String> keys) {
        LayoutInflater inflater = getLayoutInflater();
        List<View> rows = new ArrayList<>();
        int index = 0;
        for (String key : keys) {
            // 区切り線は hideOrphanDividers が見えている行の間にだけ出す。行ごとに 1 本置いておく。
            if (index > 0) features.addView(inflater.inflate(R.layout.item_divider, features, false), index++);
            TextView row = (TextView) inflater.inflate(R.layout.item_more_row, features, false);
            row.setText(MainTabs.moreLabel(key));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(MainTabs.iconRes(key), 0, R.drawable.ic_chevron_right, 0);
            int destination = MainTabs.destinationId(key);
            row.setOnClickListener(v -> Navigation.findNavController(v).navigate(destination));
            features.addView(row, index++);
            rows.add(row);
        }
        return rows;
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
