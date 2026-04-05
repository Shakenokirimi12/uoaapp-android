package com.shakenokirimi12.uoa_app.ui.course;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.AttendanceManager;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.CourseSection;
import com.shakenokirimi12.uoa_app.data.models.SyllabusData;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.services.SyllabusService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CourseDetailFragment extends Fragment {

    private static final String ARG_COURSE_ID = "course_id";
    private static final String ARG_COURSE_NAME = "course_name";
    private static final String ARG_COURSE_SHORTNAME = "course_shortname";

    private int courseId;
    private String courseName;
    private String courseShortname;

    private SyllabusData syllabusData;
    private final SyllabusService syllabusService = new SyllabusService();
    private AttendanceManager attendanceManager;
    private CalendarEvent todayEvent;

    private TextView textPresentCount, textAbsentCount, textLateCount;
    private TextView textAbsenceLabel, textDangerWarning, textTodayClassInfo;
    private ProgressBar progressAbsence, progressContents;
    private MaterialCardView cardSyllabus, cardAttendance, cardTodayAction;
    private TextView textSyllabusCredits, textSyllabusCategory;
    private LinearLayout layoutContents;
    private RecyclerView recyclerAssignments, recyclerHistory;
    private TextView textNoAssignments, textNoHistory;
    private MaterialButton btnPresent, btnLate, btnAbsent;
    private SwipeRefreshLayout swipeRefresh;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.JAPANESE);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_course_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            courseId = args.getInt(ARG_COURSE_ID);
            courseName = args.getString(ARG_COURSE_NAME, "");
            courseShortname = args.getString(ARG_COURSE_SHORTNAME, "");
        }

        attendanceManager = AttendanceManager.getInstance(requireContext());

        TextView textCourseName = view.findViewById(R.id.text_course_name);
        TextView textCourseShortname = view.findViewById(R.id.text_course_shortname);
        textCourseName.setText(courseName);
        textCourseShortname.setText(courseShortname);

        cardSyllabus = view.findViewById(R.id.card_syllabus);
        textSyllabusCredits = view.findViewById(R.id.text_syllabus_credits);
        textSyllabusCategory = view.findViewById(R.id.text_syllabus_category);
        cardAttendance = view.findViewById(R.id.card_attendance);
        cardTodayAction = view.findViewById(R.id.card_today_action);
        textPresentCount = view.findViewById(R.id.text_present_count);
        textAbsentCount = view.findViewById(R.id.text_absent_count);
        textLateCount = view.findViewById(R.id.text_late_count);
        textAbsenceLabel = view.findViewById(R.id.text_absence_label);
        progressAbsence = view.findViewById(R.id.progress_absence);
        textDangerWarning = view.findViewById(R.id.text_danger_warning);
        textTodayClassInfo = view.findViewById(R.id.text_today_class_info);
        progressContents = view.findViewById(R.id.progress_contents);
        layoutContents = view.findViewById(R.id.layout_contents);
        recyclerAssignments = view.findViewById(R.id.recycler_assignments);
        recyclerHistory = view.findViewById(R.id.recycler_history);
        textNoAssignments = view.findViewById(R.id.text_no_assignments);
        textNoHistory = view.findViewById(R.id.text_no_history);
        btnPresent = view.findViewById(R.id.btn_present);
        btnLate = view.findViewById(R.id.btn_late);
        btnAbsent = view.findViewById(R.id.btn_absent);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);

        recyclerAssignments.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerHistory.setLayoutManager(new LinearLayoutManager(requireContext()));

        btnPresent.setOnClickListener(v -> addAttendance(AttendanceManager.Status.PRESENT));
        btnLate.setOnClickListener(v -> addAttendance(AttendanceManager.Status.LATE));
        btnAbsent.setOnClickListener(v -> addAttendance(AttendanceManager.Status.ABSENT));

        cardSyllabus.setOnClickListener(v -> {
            if (syllabusData != null) {
                Bundle b = new Bundle();
                b.putString("syllabus_json", new com.google.gson.Gson().toJson(syllabusData));
                b.putString("course_name", courseName);
                Navigation.findNavController(view).navigate(R.id.action_detail_to_syllabus, b);
            }
        });

        view.findViewById(R.id.btn_moodle_browser).setOnClickListener(v -> {
            String url = "https://elms.u-aizu.ac.jp/course/view.php?id=" + courseId;
            try {
                new CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        });

        swipeRefresh.setOnRefreshListener(this::loadData);

        cardSyllabus.setVisibility(View.GONE);
        loadData();
    }

    private void loadData() {
        loadAttendance();
        checkTodayClass();
        loadSyllabus();
        loadCourseContents();
        loadAssignments();
        swipeRefresh.setRefreshing(false);
    }

    private void loadSyllabus() {
        String cid = syllabusService.extractCourseId(courseShortname);
        if (cid == null) return;

        syllabusService.fetchSyllabus(cid, new ServiceCallback<SyllabusData>() {
            @Override
            public void onSuccess(SyllabusData result) {
                if (!isAdded()) return;
                syllabusData = result;
                cardSyllabus.setVisibility(View.VISIBLE);
                textSyllabusCredits.setText(result.getCredits() != null ? result.getCredits() : "-");
                textSyllabusCategory.setText(result.getCategory() != null ? result.getCategory() : "");
            }
            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                cardSyllabus.setVisibility(View.GONE);
            }
        });
    }

    private void loadAttendance() {
        AttendanceManager.AttendanceSummary summary = attendanceManager.getAttendance(String.valueOf(courseId));
        textPresentCount.setText(String.valueOf(summary.present));
        textAbsentCount.setText(String.valueOf(summary.absent));
        textLateCount.setText(String.valueOf(summary.late));

        int absence = summary.absent;
        textAbsenceLabel.setText(String.format(Locale.JAPANESE, "欠席・遅刻許容数 (%d/5)", absence));
        progressAbsence.setProgress(Math.min(absence, 5));
        textDangerWarning.setVisibility(absence >= 5 ? View.VISIBLE : View.GONE);

        updateHistoryList(summary.history);
    }

    private void updateHistoryList(List<AttendanceManager.HistoryItem> history) {
        if (history.isEmpty()) {
            recyclerHistory.setVisibility(View.GONE);
            textNoHistory.setVisibility(View.VISIBLE);
            return;
        }
        recyclerHistory.setVisibility(View.VISIBLE);
        textNoHistory.setVisibility(View.GONE);
        recyclerHistory.setAdapter(new HistoryAdapter(history, historyId -> {
            attendanceManager.removeHistory(String.valueOf(courseId), historyId);
            loadAttendance();
        }));
    }

    private void checkTodayClass() {
        List<CalendarEvent> events = DataCache.getInstance(requireContext()).loadEvents();
        String todayStr = dateFmt.format(new Date());

        String cNameNorm = normalize(courseName);
        String sNameNorm = normalize(courseShortname);

        todayEvent = null;
        for (CalendarEvent ev : events) {
            if (ev.getDtstart() == null) continue;
            String evDate = dateFmt.format(ev.getDtstart());
            if (!evDate.equals(todayStr)) continue;
            String eName = normalize(ev.getSummary());
            if (eName.contains(cNameNorm) || eName.contains(sNameNorm) || cNameNorm.contains(eName)) {
                todayEvent = ev;
                break;
            }
        }

        if (todayEvent != null) {
            textTodayClassInfo.setText("授業あり: " + timeFmt.format(todayEvent.getDtstart()) + " ~");
            textTodayClassInfo.setTextColor(requireContext().getColor(R.color.primary));
        } else {
            textTodayClassInfo.setText("本日は授業予定がありません");
        }

        boolean canRegister = todayEvent != null;
        btnPresent.setEnabled(canRegister);
        btnLate.setEnabled(canRegister);
        btnAbsent.setEnabled(canRegister);
    }

    private void loadCourseContents() {
        progressContents.setVisibility(View.VISIBLE);
        layoutContents.removeAllViews();

        MoodleService service = new MoodleService();
        service.fetchCourseContents(courseId, new ServiceCallback<List<CourseSection>>() {
            @Override
            public void onSuccess(List<CourseSection> sections) {
                if (!isAdded()) return;
                progressContents.setVisibility(View.GONE);
                buildContentsUI(sections);
            }
            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressContents.setVisibility(View.GONE);
            }
        });
    }

    private void buildContentsUI(List<CourseSection> sections) {
        layoutContents.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (CourseSection section : sections) {
            if (section.getName().isEmpty() && section.getModules().isEmpty()) continue;

            TextView header = new TextView(requireContext());
            header.setText(section.getName());
            header.setTextSize(14);
            header.setTypeface(null, Typeface.BOLD);
            header.setTextColor(requireContext().getColor(R.color.primary));
            header.setPadding(16, 12, 16, 8);
            header.setBackgroundColor(requireContext().getColor(R.color.primary) & 0x1AFFFFFF);
            layoutContents.addView(header);

            if (!section.getSummary().isEmpty()) {
                TextView summaryText = new TextView(requireContext());
                summaryText.setText(section.getSummary());
                summaryText.setTextSize(12);
                summaryText.setPadding(16, 4, 16, 4);
                layoutContents.addView(summaryText);
            }

            for (CourseSection.CourseModule mod : section.getModules()) {
                TextView modView = new TextView(requireContext());
                modView.setText("  " + mod.getName());
                modView.setTextSize(14);
                modView.setPadding(40, 12, 16, 12);
                modView.setCompoundDrawablePadding(8);
                if (!mod.getUrl().isEmpty()) {
                    modView.setOnClickListener(v -> {
                        try {
                            new CustomTabsIntent.Builder().build()
                                    .launchUrl(requireContext(), Uri.parse(mod.getUrl()));
                        } catch (Exception e) {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mod.getUrl())));
                        }
                    });
                }
                layoutContents.addView(modView);
            }

            if (section.getModules().isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setText("モジュールはありません");
                empty.setTextSize(12);
                empty.setPadding(16, 8, 16, 8);
                layoutContents.addView(empty);
            }
        }
    }

    private void loadAssignments() {
        List<Assignment> all = DataCache.getInstance(requireContext()).loadAssignments();
        List<Assignment> courseAssignments = new ArrayList<>();
        for (Assignment a : all) {
            if (courseName.equals(a.getCourseName())) {
                courseAssignments.add(a);
            }
        }

        if (courseAssignments.isEmpty()) {
            recyclerAssignments.setVisibility(View.GONE);
            textNoAssignments.setVisibility(View.VISIBLE);
        } else {
            recyclerAssignments.setVisibility(View.VISIBLE);
            textNoAssignments.setVisibility(View.GONE);
            recyclerAssignments.setAdapter(new CourseAssignmentAdapter(courseAssignments));
        }
    }

    private void addAttendance(AttendanceManager.Status status) {
        attendanceManager.addHistory(String.valueOf(courseId), status, new Date(), "manual");
        loadAttendance();
    }

    private String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- Inner Adapters ---

    static class CourseAssignmentAdapter extends RecyclerView.Adapter<CourseAssignmentAdapter.VH> {
        private final List<Assignment> items;
        private final SimpleDateFormat fmt = new SimpleDateFormat("MM/dd(E) HH:mm", Locale.JAPANESE);

        CourseAssignmentAdapter(List<Assignment> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_assignment, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Assignment a = items.get(pos);
            h.name.setText(a.getName());
            if (a.getDueDate() > 0) {
                h.dueDate.setText("期限: " + fmt.format(new Date(a.getDueDate() * 1000)));
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView name, dueDate;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.text_assignment_name);
                dueDate = v.findViewById(R.id.text_due_date);
            }
        }
    }

    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<AttendanceManager.HistoryItem> items;
        private final OnDeleteListener listener;

        interface OnDeleteListener { void onDelete(String id); }

        HistoryAdapter(List<AttendanceManager.HistoryItem> items, OnDeleteListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AttendanceManager.HistoryItem item = items.get(pos);
            h.date.setText(item.date);
            String label;
            switch (item.status) {
                case "PRESENT": label = "出席"; break;
                case "ABSENT": label = "欠席"; break;
                case "LATE": label = "遅刻"; break;
                default: label = item.status;
            }
            if ("auto".equals(item.source)) label += " (自動)";
            h.status.setText(label);
            h.delete.setOnClickListener(v -> listener.onDelete(item.id));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView date, status;
            View delete;
            VH(View v) {
                super(v);
                date = v.findViewById(R.id.text_date);
                status = v.findViewById(R.id.text_status);
                delete = v.findViewById(R.id.btn_delete);
            }
        }
    }
}
