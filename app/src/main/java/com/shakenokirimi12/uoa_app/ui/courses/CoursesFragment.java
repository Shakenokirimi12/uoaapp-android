package com.shakenokirimi12.uoa_app.ui.courses;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.CourseAdapter;

import androidx.navigation.Navigation;

import java.util.List;

public class CoursesFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerCourses;
    private TextView textEmpty;
    private final CourseAdapter courseAdapter = new CourseAdapter();
    private final MoodleService moodleService = new MoodleService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_courses, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        recyclerCourses = view.findViewById(R.id.recycler_courses);
        recyclerCourses.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerCourses.setAdapter(courseAdapter);
        textEmpty = view.findViewById(R.id.text_empty);
        courseAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }
        });

        TextInputEditText editSearch = view.findViewById(R.id.edit_search);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                courseAdapter.setQuery(s.toString());
            }
        });

        courseAdapter.setOnCourseClickListener(course -> {
            Bundle args = new Bundle();
            args.putInt("course_id", course.getId());
            args.putString("course_name", course.getFullname());
            args.putString("course_shortname", course.getShortname());
            Navigation.findNavController(view).navigate(R.id.action_courses_to_detail, args);
        });

        swipeRefresh.setColorSchemeColors(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::loadCourses);

        List<MoodleCourse> cached = DataCache.getInstance(requireContext()).loadCourses();
        if (!cached.isEmpty()) {
            courseAdapter.setItems(cached);
        }
        // The observer only fires on adapter changes; with no cache nothing changes yet.
        updateEmptyState();

        loadCourses();
    }

    private void updateEmptyState() {
        if (textEmpty == null) return;
        boolean empty = courseAdapter.getItemCount() == 0;
        textEmpty.setText("コースがありません");
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void loadCourses() {
        swipeRefresh.setRefreshing(true);
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();

        if (user.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        // ID/PW 誤りが確定している間は自動でログインを試みない (アカウントロック対策)。
        if (com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance(requireContext()).isCredentialsInvalid()) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            android.widget.Toast.makeText(requireContext(), com.shakenokirimi12.uoa_app.services.AuthErrors.INVALID_CREDENTIALS_MESSAGE, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        moodleService.login(user, pass, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (!isAdded()) return;
                moodleService.fetchCourses(new ServiceCallback<List<MoodleCourse>>() {
                    @Override
                    public void onSuccess(List<MoodleCourse> courses) {
                        if (!isAdded()) return;
                        courseAdapter.setItems(courses);
                        swipeRefresh.setRefreshing(false);
                        DataCache.getInstance(requireContext()).saveCourses(courses);
                    }

                    @Override
                    public void onError(String message) {
                        swipeRefresh.setRefreshing(false);
                        if (isAdded()) {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                // requireContext() は detach 後に投げるので、isAdded の内側で呼ぶ
                if (isAdded()) MoodleService.markInvalidIfCredentialsError(requireContext(), message);
                swipeRefresh.setRefreshing(false);
                if (isAdded()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
