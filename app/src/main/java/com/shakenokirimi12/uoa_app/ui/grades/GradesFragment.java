package com.shakenokirimi12.uoa_app.ui.grades;

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
import com.shakenokirimi12.uoa_app.data.models.Grade;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.GradeAdapter;

import java.util.List;

public class GradesFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private final GradeAdapter gradeAdapter = new GradeAdapter();
    private final CampusSquareService csService = new CampusSquareService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_grades, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        RecyclerView recycler = view.findViewById(R.id.recycler_grades);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(gradeAdapter);

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::loadGrades);

        List<Grade> cached = DataCache.getInstance(requireContext()).loadGrades();
        if (!cached.isEmpty()) {
            gradeAdapter.setItems(cached);
        }

        loadGrades();
    }

    private void loadGrades() {
        swipeRefresh.setRefreshing(true);
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();

        if (user.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        csService.fetchGrades(user, pass, new ServiceCallback<List<Grade>>() {
            @Override
            public void onSuccess(List<Grade> grades) {
                if (!isAdded()) return;
                gradeAdapter.setItems(grades);
                swipeRefresh.setRefreshing(false);
                DataCache.getInstance(requireContext()).saveGrades(grades);
            }

            @Override
            public void onError(String message) {
                swipeRefresh.setRefreshing(false);
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
