package com.shakenokirimi12.uoa_app.ui.reviews;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Grade;
import com.shakenokirimi12.uoa_app.data.models.ReviewCourse;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.ReviewCourseAdapter;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReviewListFragment extends Fragment {

    private RecyclerView recyclerCourses;
    private TextInputEditText editSearch;
    private View buttonWriteInstructor;
    private ReviewCourseAdapter courseAdapter;
    private ReviewCourseAdapter instructorAdapter;
    private boolean isInstructorTab = false;
    private final ReviewService reviewService = new ReviewService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_review_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());

        if (!prefs.isReviewConsentGiven()) {
            showConsentDialog(prefs, view);
            return;
        }

        initUI(view, prefs);
    }

    private void showConsentDialog(PreferenceManager prefs, View view) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("授業評価機能について")
                .setMessage("不正利用防止とトラブル時のログ保持を目的に、デバイス識別子と学籍番号のハッシュ値を取得します。\n\n"
                        + "パスワードはサーバーへ送信されません。\n\n"
                        + "本機能は学生が個人で提供する非公式機能であり、会津大学とは一切関係ありません。")
                .setPositiveButton("同意する", (d, w) -> {
                    prefs.setReviewConsentGiven(true);
                    initUI(view, prefs);
                })
                .setNegativeButton("戻る", (d, w) -> {
                    if (isAdded()) requireActivity().onBackPressed();
                })
                .setCancelable(false)
                .show();
    }

    private void initUI(View view, PreferenceManager prefs) {
        recyclerCourses = view.findViewById(R.id.recycler_courses);
        recyclerCourses.setLayoutManager(new LinearLayoutManager(requireContext()));
        editSearch = view.findViewById(R.id.edit_search);
        buttonWriteInstructor = view.findViewById(R.id.button_write_instructor);

        // Course adapter (from grades)
        courseAdapter = new ReviewCourseAdapter();
        courseAdapter.setOnCourseClickListener(course -> {
            Bundle args = new Bundle();
            args.putString("course_id", course.getCourseId());
            args.putString("course_name", course.getCourseName());
            Navigation.findNavController(view).navigate(R.id.action_reviews_to_course_reviews, args);
        });

        // Instructor adapter (from API)
        instructorAdapter = new ReviewCourseAdapter();
        instructorAdapter.setOnCourseClickListener(course -> {
            Bundle args = new Bundle();
            args.putString("course_id", "instructor_" + course.getCourseId());
            args.putString("course_name", course.getCourseName());
            Navigation.findNavController(view).navigate(R.id.action_reviews_to_instructor_reviews, args);
        });

        recyclerCourses.setAdapter(courseAdapter);

        // Tab switching
        TabLayout tabType = view.findViewById(R.id.tab_type);
        tabType.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isInstructorTab = tab.getPosition() == 1;
                editSearch.setHint(isInstructorTab ? "教員名を検索" : "科目名を検索");
                editSearch.setText("");
                buttonWriteInstructor.setVisibility(isInstructorTab ? View.VISIBLE : View.GONE);
                if (isInstructorTab) {
                    recyclerCourses.setAdapter(instructorAdapter);
                    loadInstructors("");
                } else {
                    recyclerCourses.setAdapter(courseAdapter);
                    loadGrades("");
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = editSearch.getText() != null ? editSearch.getText().toString().trim() : "";
                if (isInstructorTab) loadInstructors(q);
                else loadGrades(q);
                return true;
            }
            return false;
        });

        // Write new instructor review
        buttonWriteInstructor.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("course_id", "");
            args.putString("course_name", "");
            args.putString("review_type", "instructor");
            Navigation.findNavController(v).navigate(R.id.action_reviews_to_write_review, args);
        });

        if (prefs.getReviewUserId().isEmpty() && !prefs.getUsername().isEmpty()) {
            registerUser(prefs);
        }

        view.findViewById(R.id.swipe_refresh).setEnabled(false);
        loadGrades("");
    }

    private void loadGrades(String query) {
        List<Grade> grades = DataCache.getInstance(requireContext()).loadGrades();
        Set<String> seen = new LinkedHashSet<>();
        List<ReviewCourse> items = new ArrayList<>();

        for (Grade g : grades) {
            if (g.getCourseName() == null || seen.contains(g.getCourseName())) continue;
            if (!query.isEmpty() && !g.getCourseName().toLowerCase().contains(query.toLowerCase())) continue;
            seen.add(g.getCourseName());
            ReviewCourse rc = new ReviewCourse();
            rc.setCourseId(g.getCourseName().replace(" ", "_"));
            rc.setCourseName(g.getCourseName());
            rc.setInstructor(g.getGrade() + (g.getScore() != null && !g.getScore().isEmpty() ? " (" + g.getScore() + "点)" : ""));
            items.add(rc);
        }
        courseAdapter.setItems(items);
    }

    private void loadInstructors(String query) {
        reviewService.searchInstructors(query, new ServiceCallback<List<ReviewCourse>>() {
            @Override
            public void onSuccess(List<ReviewCourse> instructors) {
                if (!isAdded()) return;
                instructorAdapter.setItems(instructors);
            }

            @Override
            public void onError(String message) {}
        });
    }

    private void registerUser(PreferenceManager prefs) {
        String deviceId = prefs.getDeviceId();
        String studentHash = sha256(prefs.getUsername());
        reviewService.register(deviceId, studentHash, new ServiceCallback<String>() {
            @Override public void onSuccess(String userId) { prefs.setReviewUserId(userId); }
            @Override public void onError(String message) {}
        });
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return input; }
    }
}
