package com.shakenokirimi12.uoa_app.ui.gakushoku;

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
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenuItem;
import com.shakenokirimi12.uoa_app.services.GakushokuService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.MenuAdapter;

import java.util.List;

public class GakushokuFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private final MenuAdapter menuAdapter = new MenuAdapter();
    private final GakushokuService gakushokuService = new GakushokuService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gakushoku, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        RecyclerView recyclerMenu = view.findViewById(R.id.recycler_menu);
        recyclerMenu.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerMenu.setAdapter(menuAdapter);

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::loadMenu);

        List<GakushokuMenuItem> cached = DataCache.getInstance(requireContext()).loadMenu();
        if (!cached.isEmpty()) {
            menuAdapter.setItems(cached);
        }

        loadMenu();
    }

    private void loadMenu() {
        swipeRefresh.setRefreshing(true);
        gakushokuService.fetchMenu(new ServiceCallback<List<GakushokuMenuItem>>() {
            @Override
            public void onSuccess(List<GakushokuMenuItem> items) {
                if (!isAdded()) return;
                menuAdapter.setItems(items);
                swipeRefresh.setRefreshing(false);
                DataCache.getInstance(requireContext()).saveMenu(items);
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
