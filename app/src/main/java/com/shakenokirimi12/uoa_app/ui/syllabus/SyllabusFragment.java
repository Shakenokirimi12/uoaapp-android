package com.shakenokirimi12.uoa_app.ui.syllabus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.SyllabusData;

public class SyllabusFragment extends Fragment {

    private SyllabusData syllabus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_syllabus, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            String json = args.getString("syllabus_json");
            if (json != null) syllabus = new Gson().fromJson(json, SyllabusData.class);
        }

        if (syllabus == null) return;

        LinearLayout basicInfo = view.findViewById(R.id.layout_basic_info);
        addInfoRow(basicInfo, "科目名", syllabus.getName());
        addInfoRow(basicInfo, "カテゴリ", syllabus.getCategory());
        addInfoRow(basicInfo, "サブカテゴリ", syllabus.getSubCategory());
        addInfoRow(basicInfo, "学期", syllabus.getSemester());
        addInfoRow(basicInfo, "対象", syllabus.getCourseFor());
        addInfoRow(basicInfo, "単位数", syllabus.getCredits());
        addInfoRow(basicInfo, "コーディネーター", syllabus.getCoordinator());
        addInfoRow(basicInfo, "担当教員", syllabus.getInstructor());
        addInfoRow(basicInfo, "推奨トラック", syllabus.getRecommendedTrack());
        addInfoRow(basicInfo, "必修科目", syllabus.getEssentialCourses());

        setSection(view, R.id.card_outline, R.id.text_outline, syllabus.getOutline());
        setSection(view, R.id.card_objectives, R.id.text_objectives, syllabus.getObjectives());
        setSection(view, R.id.card_grading, R.id.text_grading, syllabus.getGrading());
        setSection(view, R.id.card_schedule, R.id.text_schedule, syllabus.getSchedule());
        setSection(view, R.id.card_textbook, R.id.text_textbook, combineTextbooks());

        view.findViewById(R.id.btn_open_web).setOnClickListener(v -> {
            if (syllabus.getUrl() != null && !syllabus.getUrl().isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(syllabus.getUrl())));
            }
        });

        if (syllabus.getUrl() == null || syllabus.getUrl().isEmpty()) {
            view.findViewById(R.id.btn_open_web).setVisibility(View.GONE);
        }
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        if (value == null || value.isEmpty()) return;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setMinWidth(240);
        labelView.setTextColor(requireContext().getColor(R.color.primary));

        TextView valueView = new TextView(requireContext());
        valueView.setText(value);
        valueView.setTextSize(13);

        row.addView(labelView);
        row.addView(valueView);
        parent.addView(row);
    }

    private void setSection(View root, int cardId, int textId, String content) {
        View card = root.findViewById(cardId);
        if (content == null || content.isEmpty()) {
            card.setVisibility(View.GONE);
            return;
        }
        ((TextView) root.findViewById(textId)).setText(content);
    }

    private String combineTextbooks() {
        StringBuilder sb = new StringBuilder();
        if (syllabus.getTextbook() != null && !syllabus.getTextbook().isEmpty()) {
            sb.append(syllabus.getTextbook());
        }
        if (syllabus.getReference() != null && !syllabus.getReference().isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n参考書:\n");
            sb.append(syllabus.getReference());
        }
        return sb.toString();
    }
}
