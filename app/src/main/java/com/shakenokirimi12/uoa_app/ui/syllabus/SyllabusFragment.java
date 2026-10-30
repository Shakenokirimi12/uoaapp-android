package com.shakenokirimi12.uoa_app.ui.syllabus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
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
        addInfoRow(basicInfo, getString(R.string.syllabus_field_name), syllabus.getName());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_category), syllabus.getCategory());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_subcategory), syllabus.getSubCategory());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_semester), syllabus.getSemester());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_target), syllabus.getCourseFor());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_credits), syllabus.getCredits());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_coordinator), syllabus.getCoordinator());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_instructor), syllabus.getInstructor());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_recommended_track), syllabus.getRecommendedTrack());
        addInfoRow(basicInfo, getString(R.string.syllabus_field_essential_courses), syllabus.getEssentialCourses());

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

        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextAppearance(textAppearance(com.google.android.material.R.attr.textAppearanceLabelMedium));
        labelView.setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant));
        labelView.setWidth(Math.round(112 * density));

        TextView valueView = new TextView(requireContext());
        valueView.setText(value);
        valueView.setTextAppearance(textAppearance(com.google.android.material.R.attr.textAppearanceBodyMedium));
        valueView.setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface));
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        parent.addView(row);
    }

    /** Resolves a theme textAppearance attribute to its style resource. */
    private int textAppearance(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
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
            if (sb.length() > 0) sb.append(getString(R.string.syllabus_references_heading));
            sb.append(syllabus.getReference());
        }
        return sb.toString();
    }
}
