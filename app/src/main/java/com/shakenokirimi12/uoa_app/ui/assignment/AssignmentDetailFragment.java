package com.shakenokirimi12.uoa_app.ui.assignment;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.Assignment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AssignmentDetailFragment extends Fragment {

    private static final String ARG_ASSIGNMENT_ID = "assignment_id";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_assignment_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        int assignmentId = 0;
        if (getArguments() != null) {
            assignmentId = getArguments().getInt(ARG_ASSIGNMENT_ID, 0);
        }

        Assignment assignment = findAssignment(assignmentId);
        if (assignment == null) return;

        TextView textName = view.findViewById(R.id.text_assignment_name);
        TextView textCourseName = view.findViewById(R.id.text_course_name);
        TextView textDueDate = view.findViewById(R.id.text_due_date);
        TextView textDueRelative = view.findViewById(R.id.text_due_relative);
        TextView textStatus = view.findViewById(R.id.text_status);
        TextView labelDesc = view.findViewById(R.id.label_description);
        TextView textDesc = view.findViewById(R.id.text_description);

        textName.setText(assignment.getName());
        textCourseName.setText(assignment.getCourseName() != null
                ? assignment.getCourseName() : "");

        // Due date
        if (assignment.getDueDate() > 0) {
            Date dueDate = new Date(assignment.getDueDate() * 1000);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPANESE);
            textDueDate.setText(fmt.format(dueDate));

            long nowSec = System.currentTimeMillis() / 1000;
            long diffSec = assignment.getDueDate() - nowSec;
            if (diffSec < 0) {
                textDueRelative.setText("期限切れ");
                textDueRelative.setTextColor(getResources().getColor(R.color.error, null));
            } else if (diffSec < 3600) {
                textDueRelative.setText("残り" + (diffSec / 60) + "分");
                textDueRelative.setTextColor(getResources().getColor(R.color.error, null));
            } else if (diffSec < 86400) {
                textDueRelative.setText("残り" + (diffSec / 3600) + "時間");
                textDueRelative.setTextColor(getResources().getColor(R.color.primary, null));
            } else {
                textDueRelative.setText("残り" + (diffSec / 86400) + "日");
                textDueRelative.setTextColor(getResources().getColor(R.color.primary, null));
            }
        }

        // Status
        if (assignment.isSubmitted()) {
            textStatus.setText("提出済み");
            textStatus.setTextColor(getResources().getColor(R.color.primary, null));
            textStatus.setBackgroundColor(0x1A008578);
        } else {
            textStatus.setText("未提出");
            textStatus.setTextColor(getResources().getColor(R.color.error, null));
            textStatus.setBackgroundColor(0x1AB00020);
        }

        // Description
        if (assignment.getIntro() != null && !assignment.getIntro().isEmpty()) {
            labelDesc.setVisibility(View.VISIBLE);
            textDesc.setVisibility(View.VISIBLE);
            textDesc.setText(android.text.Html.fromHtml(
                    assignment.getIntro(), android.text.Html.FROM_HTML_MODE_COMPACT));
        }

        // Open in Moodle
        view.findViewById(R.id.button_open_moodle).setOnClickListener(v -> {
            String url = "https://elms.u-aizu.ac.jp/mod/assign/view.php?id=" + assignment.getId();
            new CustomTabsIntent.Builder().build()
                    .launchUrl(requireContext(), Uri.parse(url));
        });
    }

    private Assignment findAssignment(int id) {
        List<Assignment> assignments = DataCache.getInstance(requireContext()).loadAssignments();
        for (Assignment a : assignments) {
            if (a.getId() == id) return a;
        }
        return null;
    }
}
