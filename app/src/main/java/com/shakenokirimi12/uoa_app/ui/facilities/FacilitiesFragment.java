package com.shakenokirimi12.uoa_app.ui.facilities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.FacilityUsage;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.FacilityAdapter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class FacilitiesFragment extends Fragment {

    private static final String EMPTY_MESSAGE = "施設データがありません";

    private TextView textDate;
    private TextView textEmpty;
    private View layoutEmpty;
    private View buttonRetry;
    private CircularProgressIndicator progressBar;
    private RecyclerView recycler;
    private final FacilityAdapter adapter = new FacilityAdapter();
    private final CampusSquareService csService = new CampusSquareService();
    private final Calendar selectedDate = Calendar.getInstance();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 (E)", Locale.JAPANESE);
    private final SimpleDateFormat apiFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_facilities, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textDate = view.findViewById(R.id.text_date);
        textEmpty = view.findViewById(R.id.text_empty);
        layoutEmpty = view.findViewById(R.id.layout_empty);
        buttonRetry = view.findViewById(R.id.button_retry);
        buttonRetry.setOnClickListener(v -> loadFacilities());
        progressBar = view.findViewById(R.id.progress_bar);
        recycler = view.findViewById(R.id.recycler_facilities);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        view.findViewById(R.id.button_prev_day).setOnClickListener(v -> {
            selectedDate.add(Calendar.DAY_OF_YEAR, -1);
            updateDateAndLoad();
        });

        view.findViewById(R.id.button_next_day).setOnClickListener(v -> {
            selectedDate.add(Calendar.DAY_OF_YEAR, 1);
            updateDateAndLoad();
        });

        updateDateAndLoad();
    }

    private void updateDateAndLoad() {
        textDate.setText(dateFmt.format(selectedDate.getTime()));
        loadFacilities();
    }

    private void loadFacilities() {
        progressBar.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);

        String dateStr = apiFmt.format(selectedDate.getTime());
        csService.fetchFacilityUsage(dateStr, new ServiceCallback<List<FacilityUsage>>() {
            @Override
            public void onSuccess(List<FacilityUsage> facilities) {
                if (!isAdded()) return;
                progressBar.setVisibility(View.GONE);
                if (facilities.isEmpty()) {
                    textEmpty.setText(EMPTY_MESSAGE);
                    buttonRetry.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    recycler.setVisibility(View.VISIBLE);
                    adapter.setItems(facilities);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressBar.setVisibility(View.GONE);
                // Show the real error inline with a retry button instead of the generic empty text.
                // Lead with what happened; keep the raw reason underneath because it is the
                // only clue when CampusSquare itself is down.
                textEmpty.setText(message != null && message.contains("利用できません")
                        ? message
                        : "施設利用状況を取得できませんでした\n\n" + message);
                buttonRetry.setVisibility(View.VISIBLE);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }
}
