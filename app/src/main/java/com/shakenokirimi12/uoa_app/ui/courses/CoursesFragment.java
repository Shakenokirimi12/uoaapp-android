package com.shakenokirimi12.uoa_app.ui.courses;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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

        courseAdapter.setOnCourseClickListener(course -> {
            Bundle args = new Bundle();
            args.putInt("course_id", course.getId());
            args.putString("course_name", course.getFullname());
            args.putString("course_shortname", course.getShortname());
            Navigation.findNavController(view).navigate(R.id.action_courses_to_detail, args);
        });

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::loadCourses);

        List<MoodleCourse> cached = DataCache.getInstance(requireContext()).loadCourses();
        if (!cached.isEmpty()) {
            courseAdapter.setItems(cached);
        }

        loadCourses();
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
                swipeRefresh.setRefreshing(false);
                if (isAdded()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
