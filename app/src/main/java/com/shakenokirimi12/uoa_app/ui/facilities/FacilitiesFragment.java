package com.shakenokirimi12.uoa_app.ui.facilities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private TextView textDate;
    private TextView textEmpty;
    private ProgressBar progressBar;
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
        textEmpty.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);

        String dateStr = apiFmt.format(selectedDate.getTime());
        csService.fetchFacilityUsage(dateStr, new ServiceCallback<List<FacilityUsage>>() {
            @Override
            public void onSuccess(List<FacilityUsage> facilities) {
                if (!isAdded()) return;
                progressBar.setVisibility(View.GONE);
                if (facilities.isEmpty()) {
                    textEmpty.setVisibility(View.VISIBLE);
                } else {
                    recycler.setVisibility(View.VISIBLE);
                    adapter.setItems(facilities);
                }
            }

            @Override
            public void onError(String message) {
                progressBar.setVisibility(View.GONE);
                textEmpty.setVisibility(View.VISIBLE);
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
