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
import androidx.fragment.app.Fragment;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NavSettingsFragment extends Fragment {

    private static final Map<String, String> TAB_LABELS = new LinkedHashMap<>();
    static {
        TAB_LABELS.put("home", "ホーム");
        TAB_LABELS.put("calendar", "カレンダー");
        TAB_LABELS.put("courses", "履修");
        TAB_LABELS.put("grades", "成績");
        TAB_LABELS.put("gakushoku", "学食");
        TAB_LABELS.put("facilities", "施設利用");
        TAB_LABELS.put("reviews", "授業評価");
    }

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
        });
    }

    private void loadTabs() {
        mainTabs = new ArrayList<>(Arrays.asList(prefs.getMainTabs().split(",")));
        otherTabs = new ArrayList<>(Arrays.asList(prefs.getOtherTabs().split(",")));
        mainTabs.removeIf(s -> s.isEmpty());
        otherTabs.removeIf(s -> s.isEmpty());

        // Ensure all tabs are present
        for (String key : TAB_LABELS.keySet()) {
            if (!mainTabs.contains(key) && !otherTabs.contains(key)) {
                otherTabs.add(key);
            }
        }
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
        String label = TAB_LABELS.getOrDefault(tabKey, tabKey);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView text = new TextView(requireContext());
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(getResources().getColor(R.color.text_primary, null));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textParams);
        row.addView(text);

        ImageView arrow = new ImageView(requireContext());
        arrow.setColorFilter(isMain
                ? getResources().getColor(R.color.text_secondary, null)
                : getResources().getColor(R.color.primary, null));
        arrow.setImageResource(isMain
                ? android.R.drawable.arrow_down_float
                : android.R.drawable.arrow_up_float);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrow.setLayoutParams(arrowParams);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            if (isMain) {
                mainTabs.remove(tabKey);
                otherTabs.add(tabKey);
            } else {
                if (mainTabs.size() >= 4) return;
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
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
