package com.shakenokirimi12.uoa_app.ui.gakushoku;

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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.color.MaterialColors;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;
import com.shakenokirimi12.uoa_app.services.GakushokuService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.MenuAdapter;
import com.shakenokirimi12.uoa_app.ui.util.FetchStatus;

public class GakushokuFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView textEmpty;
    private View fetchNotice;
    /** 直近の取得が失敗した (キャッシュは出したまま帯で知らせる)。 */
    private boolean failed;
    private com.google.android.material.button.MaterialButton buttonShowPast;
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
        fetchNotice = view.findViewById(R.id.fetch_notice);
        RecyclerView recyclerMenu = view.findViewById(R.id.recycler_menu);
        recyclerMenu.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerMenu.setAdapter(menuAdapter);

        swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(() -> loadMenu(true));
        buttonShowPast = view.findViewById(R.id.button_show_past);
        buttonShowPast.setOnClickListener(v -> {
            menuAdapter.setShowPast(true);
            updateEmptyState();
        });

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
                // 先に保存する。空表示の判定は保存時刻 (fetchedAt) を見るので、後だと空のメニューが「取得中」扱いになる。
                DataCache.getInstance(requireContext()).saveMenu(menu);
                failed = false;
                FetchStatus.hide(fetchNotice);
                menuAdapter.setWeeks(menu.weeksSortedFromToday());
                updateEmptyState();
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onError(String message) {
                swipeRefresh.setRefreshing(false);
                if (!isAdded()) return;
                // 以前は Toast だけで、空表示は「メニューがありません」のままだった (取れなかっただけなのに)。
                // 他の画面と同じく、キャッシュは出したまま帯で最終更新を示す。
                failed = true;
                FetchStatus.showFailure(fetchNotice,
                        DataCache.getInstance(requireContext()).fetchedAt(DataCache.Dataset.MENU));
                updateEmptyState();
            }
        });
    }

    private void updateEmptyState() {
        if (textEmpty == null) return;
        // 学食はログイン不要。一度も取れていなければ「メニューがありません」とは言わない。
        FetchStatus.Empty kind = FetchStatus.emptyKind(true, failed,
                DataCache.getInstance(requireContext()).fetchedAt(DataCache.Dataset.MENU));
        if (kind == FetchStatus.Empty.UNAVAILABLE) textEmpty.setText("メニューを取得できませんでした");
        else if (kind == FetchStatus.Empty.NONE) textEmpty.setText("メニューがありません");
        boolean empty = menuAdapter.getItemCount() == 0;
        textEmpty.setVisibility(empty && kind != FetchStatus.Empty.LOADING ? View.VISIBLE : View.GONE);
        if (buttonShowPast != null) {
            int hidden = menuAdapter.hiddenPastCount();
            buttonShowPast.setVisibility(hidden > 0 ? View.VISIBLE : View.GONE);
            buttonShowPast.setText("過ぎた " + hidden + " 日分を表示");
        }
    }
}
