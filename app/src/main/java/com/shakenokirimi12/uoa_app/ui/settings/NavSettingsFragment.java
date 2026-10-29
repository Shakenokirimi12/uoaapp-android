package com.shakenokirimi12.uoa_app.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.ui.util.MainTabs;

import java.util.List;

public class NavSettingsFragment extends Fragment {

    private PreferenceManager prefs;
    private List<String> mainTabs;
    private List<String> otherTabs;
    private LinearLayout layoutMainTabs;
    private LinearLayout layoutOtherTabs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_nav_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getInstance(requireContext());
        layoutMainTabs = view.findViewById(R.id.layout_main_tabs);
        layoutOtherTabs = view.findViewById(R.id.layout_other_tabs);

        loadTabs();
        buildUI();

        view.findViewById(R.id.button_reset_nav).setOnClickListener(v -> {
            prefs.setMainTabs("home,calendar,courses,gakushoku");
            prefs.setOtherTabs("facilities,grades");
            loadTabs();
            buildUI();
            // 入れ替えたときと同じく、表示中の下タブにもすぐ反映する。
            refreshBottomTabs();
        });
    }

    private void loadTabs() {
        // 下タブと「その他」の画面が実際に使う並びをそのまま出す (MainTabs)。保存値をそのまま
        // 出すと、未知のキーが行になったり、常に下タブにあるホームが「その他」側に見えたりする。
        mainTabs = MainTabs.mainTabKeys(prefs);
        otherTabs = MainTabs.moreTabKeys(prefs);
    }

    private void buildUI() {
        layoutMainTabs.removeAllViews();
        layoutOtherTabs.removeAllViews();

        for (String tab : mainTabs) {
            addTabRow(layoutMainTabs, tab, true);
        }
        for (String tab : otherTabs) {
            addTabRow(layoutOtherTabs, tab, false);
        }
    }

    private void addTabRow(LinearLayout parent, String tabKey, boolean isMain) {
        String label = MainTabs.label(tabKey);
        // ホームは起動時の画面なので、下タブから外せない (MainTabs.mainTabKeys が先頭に戻す)。
        boolean movable = !(isMain && MainTabs.HOME.equals(tabKey));

        if (parent.getChildCount() > 0) {
            MaterialDivider divider = new MaterialDivider(requireContext(), null,
                    com.google.android.material.R.attr.materialDividerStyle);
            divider.setDividerInsetStart(dp(16));
            divider.setDividerInsetEnd(dp(16));
            parent.addView(divider);
        }

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.util.TypedValue ripple = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true);
        if (movable) row.setBackgroundResource(ripple.resourceId);

        TextView text = new TextView(requireContext());
        text.setText(label);
        android.util.TypedValue bodyLarge = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.textAppearanceBodyLarge, bodyLarge, true);
        TextViewCompat.setTextAppearance(text, bodyLarge.resourceId);
        text.setTextColor(MaterialColors.getColor(text, com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textParams);
        row.addView(text);

        // Down = move out of the bar, up = promote into the bar.
        ImageView arrow = new ImageView(requireContext());
        arrow.setImageResource(isMain ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_up);
        arrow.setColorFilter(MaterialColors.getColor(arrow, isMain
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : androidx.appcompat.R.attr.colorPrimary));
        arrow.setContentDescription(getString(isMain
                ? R.string.nav_settings_move_to_more_description : R.string.nav_settings_move_to_bottom_description));
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrow.setLayoutParams(arrowParams);
        if (movable) row.addView(arrow);

        if (movable) row.setOnClickListener(v -> {
            if (isMain) {
                mainTabs.remove(tabKey);
                otherTabs.add(tabKey);
            } else {
                if (mainTabs.size() >= MainTabs.MAX_MAIN) return;
                otherTabs.remove(tabKey);
                mainTabs.add(tabKey);
            }
            saveTabs();
            buildUI();
        });

        parent.addView(row);
    }

    private void saveTabs() {
        prefs.setMainTabs(String.join(",", mainTabs));
        prefs.setOtherTabs(String.join(",", otherTabs));
        refreshBottomTabs();
    }

    private void refreshBottomTabs() {
        if (getActivity() instanceof com.shakenokirimi12.uoa_app.MainActivity) {
            ((com.shakenokirimi12.uoa_app.MainActivity) getActivity()).refreshBottomTabs();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
