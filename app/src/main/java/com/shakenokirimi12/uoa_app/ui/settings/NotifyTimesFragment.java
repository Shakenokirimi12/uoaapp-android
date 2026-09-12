package com.shakenokirimi12.uoa_app.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipDrawable;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class NotifyTimesFragment extends Fragment {

    // 課題の締切に対する通知タイミング。iOS と同じ id/分数にしてサーバー側の予約を共通にする。
    // 以前は授業開始向けのラベルが付いていたが、それに対応する通知はどこにも実装されていなかった。
    private static final String[][] DEFAULT_OPTIONS = {
            {"prev_10", "10分前"},
            {"prev_20", "20分前"},
            {"prev_30", "30分前"},
            {"prev_60", "1時間前"},
            {"prev_120", "2時間前"},
            {"prev_180", "3時間前"},
            {"prev_360", "6時間前"},
            {"prev_720", "12時間前"},
            {"prev_1440", "1日前"},
            {"prev_2880", "2日前"},
            {"prev_4320", "3日前"},
            {"prev_10080", "1週間前"},
    };

    private PreferenceManager prefs;
    private Set<String> selectedTimes;
    private ChipGroup layoutDefaultTimes;
    private ChipGroup layoutCustomTimes;
    private View labelCustom;
    private View cardCustomTimes;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notify_times, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getInstance(requireContext());
        String saved = prefs.getSelectedNotifyTimes();
        selectedTimes = new HashSet<>(Arrays.asList(saved.split(",")));

        layoutDefaultTimes = view.findViewById(R.id.layout_default_times);
        layoutCustomTimes = view.findViewById(R.id.layout_custom_times);
        labelCustom = view.findViewById(R.id.label_custom);
        cardCustomTimes = view.findViewById(R.id.card_custom_times);

        buildDefaultOptions();
        buildCustomOptions();

        view.findViewById(R.id.button_add_custom).setOnClickListener(v -> showAddCustomDialog());
    }

    private void buildDefaultOptions() {
        layoutDefaultTimes.removeAllViews();
        for (String[] option : DEFAULT_OPTIONS) {
            String key = option[0];
            String label = option[1];
            boolean checked = selectedTimes.contains(key);
            addOptionRow(layoutDefaultTimes, key, label, checked, false);
        }
    }

    private void buildCustomOptions() {
        layoutCustomTimes.removeAllViews();
        try {
            JSONArray customs = new JSONArray(prefs.getCustomNotifyTimes());
            if (customs.length() > 0) {
                labelCustom.setVisibility(View.VISIBLE);
                cardCustomTimes.setVisibility(View.VISIBLE);
                for (int i = 0; i < customs.length(); i++) {
                    JSONObject obj = customs.getJSONObject(i);
                    String id = obj.getString("id");
                    String label = obj.getString("label");
                    boolean checked = selectedTimes.contains(id);
                    addOptionRow(layoutCustomTimes, id, label, checked, true);
                }
            } else {
                labelCustom.setVisibility(View.GONE);
                cardCustomTimes.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            labelCustom.setVisibility(View.GONE);
            cardCustomTimes.setVisibility(View.GONE);
        }
    }

    private void addOptionRow(ChipGroup parent, String key, String label,
                              boolean checked, boolean canDelete) {
        // Filter chip: the checked icon replaces the old check ImageView.
        Chip chip = new Chip(requireContext());
        chip.setChipDrawable(ChipDrawable.createFromAttributes(
                requireContext(), null, 0,
                com.google.android.material.R.style.Widget_Material3_Chip_Filter));
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);

        chip.setOnClickListener(v -> {
            boolean nowSelected = selectedTimes.contains(key);
            if (nowSelected) {
                selectedTimes.remove(key);
            } else {
                selectedTimes.add(key);
            }
            chip.setChecked(!nowSelected);
            saveSelectedTimes();
        });

        if (canDelete) {
            chip.setCloseIconVisible(true);
            View.OnClickListener confirmDelete = v ->
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("削除")
                            .setMessage(label + " を削除しますか？")
                            .setPositiveButton("削除", (d, w) -> {
                                selectedTimes.remove(key);
                                removeCustomTime(key);
                                saveSelectedTimes();
                                buildCustomOptions();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
            chip.setOnCloseIconClickListener(confirmDelete);
            chip.setOnLongClickListener(v -> {
                confirmDelete.onClick(v);
                return true;
            });
        }

        parent.addView(chip);
    }

    private void showAddCustomDialog() {
        LinearLayout dialogLayout = new LinearLayout(requireContext());
        dialogLayout.setOrientation(LinearLayout.HORIZONTAL);
        dialogLayout.setGravity(android.view.Gravity.CENTER);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));

        NumberPicker pickerDays = createPicker(0, 7, "日");
        NumberPicker pickerHours = createPicker(0, 23, "時間");
        NumberPicker pickerMinutes = createPicker(0, 59, "分");
        pickerMinutes.setValue(10);

        dialogLayout.addView(pickerDays);
        dialogLayout.addView(createLabel("日"));
        dialogLayout.addView(pickerHours);
        dialogLayout.addView(createLabel("時間"));
        dialogLayout.addView(pickerMinutes);
        dialogLayout.addView(createLabel("分"));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("タイミングを追加")
                .setView(dialogLayout)
                .setPositiveButton("追加", (d, w) -> {
                    int totalMin = pickerDays.getValue() * 24 * 60
                            + pickerHours.getValue() * 60
                            + pickerMinutes.getValue();
                    if (totalMin <= 0) return;

                    StringBuilder label = new StringBuilder();
                    if (pickerDays.getValue() > 0) label.append(pickerDays.getValue()).append("日");
                    if (pickerHours.getValue() > 0) label.append(pickerHours.getValue()).append("時間");
                    if (pickerMinutes.getValue() > 0 || label.length() == 0)
                        label.append(pickerMinutes.getValue()).append("分");
                    label.append("前");

                    String id = UUID.randomUUID().toString();
                    addCustomTime(id, label.toString(), totalMin);
                    selectedTimes.add(id);
                    saveSelectedTimes();
                    buildCustomOptions();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private NumberPicker createPicker(int min, int max, String label) {
        NumberPicker picker = new NumberPicker(requireContext());
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setWrapSelectorWheel(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(60), ViewGroup.LayoutParams.WRAP_CONTENT);
        picker.setLayoutParams(params);
        return picker;
    }

    private TextView createLabel(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(4), 0, dp(8), 0);
        return tv;
    }

    private void saveSelectedTimes() {
        selectedTimes.remove("");
        prefs.setSelectedNotifyTimes(String.join(",", selectedTimes));
    }

    private void addCustomTime(String id, String label, int minutesBefore) {
        try {
            JSONArray arr = new JSONArray(prefs.getCustomNotifyTimes());
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("label", label);
            obj.put("minutesBefore", minutesBefore);
            arr.put(obj);
            prefs.setCustomNotifyTimes(arr.toString());
        } catch (Exception ignored) {}
    }

    private void removeCustomTime(String id) {
        try {
            JSONArray arr = new JSONArray(prefs.getCustomNotifyTimes());
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (!arr.getJSONObject(i).getString("id").equals(id)) {
                    newArr.put(arr.getJSONObject(i));
                }
            }
            prefs.setCustomNotifyTimes(newArr.toString());
        } catch (Exception ignored) {}
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
