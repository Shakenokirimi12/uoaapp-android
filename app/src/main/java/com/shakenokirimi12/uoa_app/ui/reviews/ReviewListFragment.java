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

public class ReviewListFragment extends Fragment implements ReviewGuidelinesDialogFragment.Listener {

    private RecyclerView recyclerCourses;
    private TextInputEditText editSearch;
    private View buttonWriteInstructor;
    private View buttonWriteCourse;
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

        showGuidelinesThenInit(view, prefs);
    }

    /**
     * データ利用の同意 (上のダイアログ) とは別に、投稿ルールへの同意を取る (iOS と同じ 2 段構え)。
     * 未同意のまま一覧を出さないのは、誹謗中傷や個人情報の投稿を防ぐ前提として
     * 必ず一度は読んでもらうため。
     */
    private void showGuidelinesThenInit(View view, PreferenceManager prefs) {
        if (prefs.isReviewGuidelinesAccepted()) {
            initUI(view, prefs);
            return;
        }
        ReviewGuidelinesDialogFragment.showIfNeeded(getChildFragmentManager());
    }

    @Override
    public void onAgreed() {
        if (isAdded() && getView() != null) initUI(getView(), PreferenceManager.getInstance(requireContext()));
    }

    @Override
    public void onDisagreed() {
        if (isAdded()) requireActivity().getOnBackPressedDispatcher().onBackPressed();
    }

    private void showConsentDialog(PreferenceManager prefs, View view) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("授業評価機能について")
                .setMessage("不正利用防止とトラブル時のログ保持を目的に、デバイス識別子と学籍番号のハッシュ値を取得します。\n\n"
                        + "パスワードはサーバーへ送信されません。\n\n"
                        + "本機能は学生が個人で提供する非公式機能であり、会津大学とは一切関係ありません。")
                .setPositiveButton("同意する", (d, w) -> {
                    prefs.setReviewConsentGiven(true);
                    showGuidelinesThenInit(view, prefs);
                })
                .setNegativeButton("戻る", (d, w) -> {
                    if (isAdded()) requireActivity().getOnBackPressedDispatcher().onBackPressed();
                })
                .setCancelable(false)
                .show();
    }

    private void initUI(View view, PreferenceManager prefs) {
        recyclerCourses = view.findViewById(R.id.recycler_courses);
        recyclerCourses.setLayoutManager(new LinearLayoutManager(requireContext()));
        editSearch = view.findViewById(R.id.edit_search);
        buttonWriteInstructor = view.findViewById(R.id.button_write_instructor);
        buttonWriteCourse = view.findViewById(R.id.button_write_course);

        // Course adapter: courses that already have reviews, from the server (same as iOS).
        // Listing the grades cache here was wrong twice over: it is empty until CampusSquare
        // has been fetched, and it keyed courses by name while the server keys by subject code.
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
                buttonWriteCourse.setVisibility(isInstructorTab ? View.GONE : View.VISIBLE);
                if (isInstructorTab) {
                    recyclerCourses.setAdapter(instructorAdapter);
                    loadInstructors("");
                } else {
                    recyclerCourses.setAdapter(courseAdapter);
                    loadCourses("");
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = editSearch.getText() != null ? editSearch.getText().toString().trim() : "";
                if (isInstructorTab) loadInstructors(q);
                else loadCourses(q);
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

        // Write a course review: pick one of the user's own courses (grades cache), keyed by
        // subject code so it lines up with reviews written from iOS.
        buttonWriteCourse.setOnClickListener(v -> showTakenCoursePicker(v));

        if (prefs.getReviewUserId().isEmpty() && !prefs.getUsername().isEmpty()) {
            registerUser(prefs);
        }

        view.findViewById(R.id.swipe_refresh).setEnabled(false);
        loadCourses("");
    }

    private void loadCourses(String query) {
        reviewService.searchCourses(query, new ServiceCallback<List<ReviewCourse>>() {
            @Override
            public void onSuccess(List<ReviewCourse> courses) {
                if (!isAdded()) return;
                courseAdapter.setItems(courses);
                showEmptyIfNeeded(courses.isEmpty(), "まだレビューのある授業がありません。\n下のボタンから履修した授業のレビューを書けます。");
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                courseAdapter.setItems(new ArrayList<>());
                showEmptyIfNeeded(true, message);
            }
        });
    }

    private void showEmptyIfNeeded(boolean empty, String message) {
        View v = getView();
        if (v == null) return;
        TextView textEmpty = v.findViewById(R.id.text_empty);
        textEmpty.setText(message);
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showTakenCoursePicker(View anchor) {
        List<Grade> grades = DataCache.getInstance(requireContext()).loadGrades();
        Set<String> seen = new LinkedHashSet<>();
        List<Grade> taken = new ArrayList<>();
        for (Grade g : grades) {
            String code = g.getSubjectCode();
            if (code == null || code.isEmpty() || g.getCourseName() == null || seen.contains(code)) continue;
            seen.add(code);
            taken.add(g);
        }
        if (taken.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("履修した授業が見つかりません")
                    .setMessage("先に「成績一覧」を開いて履修データを読み込んでください。")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[taken.size()];
        for (int i = 0; i < taken.size(); i++) {
            Grade g = taken.get(i);
            String year = g.getYear() != null && !g.getYear().isEmpty() ? g.getYear() + " " : "";
            labels[i] = year + g.getCourseName();
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("レビューを書く授業")
                .setItems(labels, (d, which) -> {
                    Grade g = taken.get(which);
                    Bundle args = new Bundle();
                    args.putString("course_id", g.getSubjectCode());
                    args.putString("course_name", g.getCourseName());
                    args.putString("instructor", g.getInstructor() != null ? g.getInstructor() : "");
                    args.putString("review_type", "course");
                    Navigation.findNavController(anchor).navigate(R.id.action_reviews_to_write_review, args);
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void loadInstructors(String query) {
        reviewService.searchInstructors(query, new ServiceCallback<List<ReviewCourse>>() {
            @Override
            public void onSuccess(List<ReviewCourse> instructors) {
                if (!isAdded()) return;
                instructorAdapter.setItems(instructors);
                showEmptyIfNeeded(instructors.isEmpty(), "教員が見つかりません");
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                instructorAdapter.setItems(new ArrayList<>());
                showEmptyIfNeeded(true, message);
            }
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
