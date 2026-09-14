package com.shakenokirimi12.uoa_app.ui.gakushoku;

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
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;
import com.shakenokirimi12.uoa_app.services.GakushokuService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.MenuAdapter;

public class GakushokuFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView textEmpty;
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
        textEmpty = view.findViewById(R.id.text_empty);
        RecyclerView recyclerMenu = view.findViewById(R.id.recycler_menu);
        recyclerMenu.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerMenu.setAdapter(menuAdapter);

        swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(() -> loadMenu(true));

        GakushokuMenu cached = DataCache.getInstance(requireContext()).loadMenu();
        if (cached != null) {
            menuAdapter.setWeeks(cached.weeksSortedFromToday());
        }
        updateEmptyState();

        loadMenu(false);
    }

    /** @param force true for pull-to-refresh; false skips the fetch while the cache is fresh (menu changes daily at most). */
    private void loadMenu(boolean force) {
        DataCache cache = DataCache.getInstance(requireContext());
        if (!force && cache.loadMenu() != null && cache.isFresh(DataCache.Dataset.MENU, DataCache.MENU_MAX_AGE_MS)) {
            return;
        }
        swipeRefresh.setRefreshing(true);
        gakushokuService.fetchMenu(new ServiceCallback<GakushokuMenu>() {
            @Override
            public void onSuccess(GakushokuMenu menu) {
                if (!isAdded()) return;
                menuAdapter.setWeeks(menu.weeksSortedFromToday());
                updateEmptyState();
                swipeRefresh.setRefreshing(false);
                DataCache.getInstance(requireContext()).saveMenu(menu);
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

    private void updateEmptyState() {
        if (textEmpty == null) return;
        textEmpty.setVisibility(menuAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
}
