package com.shakenokirimi12.uoa_app.ui.grades;

import android.os.Bundle;
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
    private TextView textEmpty;
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
        textEmpty = view.findViewById(R.id.text_empty);
        RecyclerView recycler = view.findViewById(R.id.recycler_grades);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(gradeAdapter);

        swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(() -> loadGrades(true));

        List<Grade> cached = DataCache.getInstance(requireContext()).loadGrades();
        if (!cached.isEmpty()) {
            gradeAdapter.setItems(cached);
        }
        updateEmptyState();

        loadGrades(false);
    }

    /** @param force true for pull-to-refresh; false skips the fetch while the cache is fresh. */
    private void loadGrades(boolean force) {
        if (!force && DataCache.getInstance(requireContext()).isFresh(DataCache.Dataset.GRADES, DataCache.DEFAULT_MAX_AGE_MS)) {
            return;
        }
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
        csService.fetchGrades(user, pass, new ServiceCallback<List<Grade>>() {
            @Override
            public void onSuccess(List<Grade> grades) {
                if (!isAdded()) return;
                gradeAdapter.setItems(grades);
                updateEmptyState();
                swipeRefresh.setRefreshing(false);
                DataCache.getInstance(requireContext()).saveGrades(grades);
            }

            @Override
            public void onError(String message) {
                if (isAdded()) com.shakenokirimi12.uoa_app.services.AuthErrors.markInvalidIfCredentialsError(requireContext(), message);
                swipeRefresh.setRefreshing(false);
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateEmptyState() {
        if (textEmpty == null) return;
        textEmpty.setVisibility(gradeAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
}
