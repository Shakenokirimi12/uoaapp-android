package com.shakenokirimi12.uoa_app.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenuItem;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.GakushokuService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.services.notification.ClassNotificationService;
import com.shakenokirimi12.uoa_app.ui.ClassDetailDialog;
import com.shakenokirimi12.uoa_app.ui.ClassLocationDialog;
import com.shakenokirimi12.uoa_app.ui.adapters.AssignmentAdapter;
import com.shakenokirimi12.uoa_app.ui.adapters.ClassAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerClasses;
    private RecyclerView recyclerAssignments;
    private TextView textNoClasses;
    private TextView textNoAssignments;
    private TextView textDate;
    private TextView textTodayHint;

    // Summary cards
    private TextView textClassCount;
    private TextView textAssignmentCount;

    // Sync status
    private View syncStatusBar;
    private TextView textSyncStatus;
    private TextView textLastSync;

    // Cafeteria preview
    private View cardGakushoku;
    private LinearLayout layoutMenuContent;
    private TextView textMenuLoading;
    private TextView textMenuLunch;
    private TextView textMenuFish;
    private TextView textMenuSalad;
    private TextView textMenuDinner;

    private ClassAdapter classAdapter;
    private AssignmentAdapter assignmentAdapter;
    private final MoodleService moodleService = new MoodleService();
    private final CampusSquareService csService = new CampusSquareService();
    private final GakushokuService gakushokuService = new GakushokuService();

    private final Calendar selectedDate = Calendar.getInstance();
    private List<CalendarEvent> allEvents = new ArrayList<>();
    private List<Assignment> allAssignments = new ArrayList<>();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 (E)", Locale.JAPANESE);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        recyclerClasses = view.findViewById(R.id.recycler_classes);
        recyclerAssignments = view.findViewById(R.id.recycler_assignments);
        textNoClasses = view.findViewById(R.id.text_no_classes);
        textNoAssignments = view.findViewById(R.id.text_no_assignments);
        textDate = view.findViewById(R.id.text_date);
        textTodayHint = view.findViewById(R.id.text_today_hint);

        // Summary cards
        textClassCount = view.findViewById(R.id.text_class_count);
        textAssignmentCount = view.findViewById(R.id.text_assignment_count);

        // Sync status
        syncStatusBar = view.findViewById(R.id.sync_status_bar);
        textSyncStatus = view.findViewById(R.id.text_sync_status);
        textLastSync = view.findViewById(R.id.text_last_sync);

        // Cafeteria preview
        cardGakushoku = view.findViewById(R.id.card_gakushoku);
        layoutMenuContent = view.findViewById(R.id.layout_menu_content);
        textMenuLoading = view.findViewById(R.id.text_menu_loading);
        textMenuLunch = view.findViewById(R.id.text_menu_lunch);
        textMenuFish = view.findViewById(R.id.text_menu_fish);
        textMenuSalad = view.findViewById(R.id.text_menu_salad);
        textMenuDinner = view.findViewById(R.id.text_menu_dinner);

        classAdapter = new ClassAdapter();
        classAdapter.setOnClassClickListener(cls ->
                ClassLocationDialog.show(requireContext(), cls));
        assignmentAdapter = new AssignmentAdapter();
        assignmentAdapter.setOnAssignmentClickListener(assignment -> {
            Bundle args = new Bundle();
            args.putInt("assignment_id", assignment.getId());
            try {
                Navigation.findNavController(requireView())
                        .navigate(R.id.navigation_assignment_detail, args);
            } catch (Exception ignored) {}
        });
        recyclerClasses.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerAssignments.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerClasses.setAdapter(classAdapter);
        recyclerAssignments.setAdapter(assignmentAdapter);

        updateDateDisplay();
        showLastSyncTime();

        view.findViewById(R.id.button_prev_day).setOnClickListener(v -> {
            selectedDate.add(Calendar.DAY_OF_YEAR, -1);
            onDateChanged();
        });

        view.findViewById(R.id.button_next_day).setOnClickListener(v -> {
            selectedDate.add(Calendar.DAY_OF_YEAR, 1);
            onDateChanged();
        });

        textTodayHint.setOnClickListener(v -> {
            selectedDate.setTime(new Date());
            onDateChanged();
        });

        cardGakushoku.setOnClickListener(v -> {
            try {
                Navigation.findNavController(v).navigate(R.id.navigation_gakushoku);
            } catch (Exception ignored) {}
        });

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(() -> {
            syncData();
            loadCafeteriaMenu();
        });

        DataCache cache = DataCache.getInstance(requireContext());
        allEvents = cache.loadEvents();
        allAssignments = cache.loadAssignments();
        if (!allEvents.isEmpty() || !allAssignments.isEmpty()) {
            filterAndDisplay();
        }

        syncData();
        loadCafeteriaMenu();
    }

    private void onDateChanged() {
        updateDateDisplay();
        filterAndDisplay();
    }

    private void updateDateDisplay() {
        textDate.setText(dateFmt.format(selectedDate.getTime()));

        Calendar today = Calendar.getInstance();
        boolean isToday = selectedDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && selectedDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
        textTodayHint.setVisibility(isToday ? View.GONE : View.VISIBLE);
    }

    private void showLastSyncTime() {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        long lastSync = prefs.getLastSync();
        if (lastSync > 0) {
            long diffMs = System.currentTimeMillis() - lastSync;
            long diffMin = diffMs / 60000;
            String timeAgo;
            if (diffMin < 1) timeAgo = "たった今";
            else if (diffMin < 60) timeAgo = diffMin + "分前";
            else if (diffMin < 1440) timeAgo = (diffMin / 60) + "時間前";
            else timeAgo = (diffMin / 1440) + "日前";

            textLastSync.setText(getString(R.string.home_last_synced, timeAgo));
            textLastSync.setVisibility(View.VISIBLE);
        }
    }

    private void filterAndDisplay() {
        Calendar dayStart = (Calendar) selectedDate.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        long start = dayStart.getTimeInMillis();
        dayStart.add(Calendar.DAY_OF_YEAR, 1);
        long end = dayStart.getTimeInMillis();

        List<CalendarEvent> dayClasses = new ArrayList<>();
        for (CalendarEvent e : allEvents) {
            if (e.getDtstart() == null) continue;
            long t = e.getDtstart().getTime();
            if (t >= start && t < end) dayClasses.add(e);
        }
        List<GroupedClass> grouped = GroupedClass.groupBySubject(dayClasses);
        classAdapter.setItems(grouped);
        textNoClasses.setVisibility(grouped.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerClasses.setVisibility(grouped.isEmpty() ? View.GONE : View.VISIBLE);

        List<Assignment> dayAssignments = new ArrayList<>();
        long startSec = start / 1000;
        long endSec = end / 1000;
        for (Assignment a : allAssignments) {
            if (a.getDueDate() >= startSec && a.getDueDate() < endSec) {
                dayAssignments.add(a);
            }
        }
        assignmentAdapter.setItems(dayAssignments);
        textNoAssignments.setVisibility(dayAssignments.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerAssignments.setVisibility(dayAssignments.isEmpty() ? View.GONE : View.VISIBLE);

        // Update summary cards
        textClassCount.setText(String.valueOf(grouped.size()));
        textAssignmentCount.setText(String.valueOf(dayAssignments.size()));
    }

    private void syncData() {
        syncStatusBar.setVisibility(View.VISIBLE);
        textSyncStatus.setText(R.string.home_sync_status);
        swipeRefresh.setRefreshing(true);
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();

        if (user.isEmpty() || pass.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            syncStatusBar.setVisibility(View.GONE);
            textNoClasses.setVisibility(View.VISIBLE);
            textNoAssignments.setVisibility(View.VISIBLE);
            textClassCount.setText("0");
            textAssignmentCount.setText("0");
            return;
        }

        csService.fetchCalendarEvents(user, pass, new ServiceCallback<List<CalendarEvent>>() {
            @Override
            public void onSuccess(List<CalendarEvent> events) {
                if (!isAdded()) return;
                allEvents = events;
                filterAndDisplay();
                startClassNotification(events);
                DataCache.getInstance(requireContext()).saveEvents(events);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                textNoClasses.setVisibility(View.VISIBLE);
            }
        });

        moodleService.login(user, pass, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (!isAdded()) return;
                moodleService.fetchAssignments(new ServiceCallback<List<Assignment>>() {
                    @Override
                    public void onSuccess(List<Assignment> assignments) {
                        if (!isAdded()) return;
                        allAssignments = assignments;
                        filterAndDisplay();
                        swipeRefresh.setRefreshing(false);
                        syncStatusBar.setVisibility(View.GONE);
                        prefs.setLastSync(System.currentTimeMillis());
                        showLastSyncTime();
                        DataCache.getInstance(requireContext()).saveAssignments(assignments);
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        swipeRefresh.setRefreshing(false);
                        syncStatusBar.setVisibility(View.GONE);
                        showError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                syncStatusBar.setVisibility(View.GONE);
                showError(message);
            }
        });
    }

    private void loadCafeteriaMenu() {
        textMenuLoading.setVisibility(View.VISIBLE);
        layoutMenuContent.setVisibility(View.GONE);

        gakushokuService.fetchMenu(new ServiceCallback<List<GakushokuMenuItem>>() {
            @Override
            public void onSuccess(List<GakushokuMenuItem> items) {
                if (!isAdded()) return;
                Calendar cal = Calendar.getInstance();
                int todayDay = cal.get(Calendar.DAY_OF_MONTH);
                GakushokuMenuItem todayMenu = null;
                for (GakushokuMenuItem item : items) {
                    if (item.getDate() != null && item.getDate().contains(todayDay + "日")) {
                        todayMenu = item;
                        break;
                    }
                }
                if (todayMenu != null) {
                    showMenuPreview(todayMenu);
                } else {
                    textMenuLoading.setText("今日のメニューはありません");
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                textMenuLoading.setText("メニュー取得失敗");
            }
        });
    }

    private void showMenuPreview(GakushokuMenuItem menu) {
        textMenuLoading.setVisibility(View.GONE);
        layoutMenuContent.setVisibility(View.VISIBLE);

        setMenuLine(textMenuLunch, "🍽 ランチ: ", menu.getLunch());
        setMenuLine(textMenuFish, "🐟 お魚: ", menu.getFish());
        setMenuLine(textMenuSalad, "🥗 サラダ: ", menu.getSalad());
        setMenuLine(textMenuDinner, "🌙 夕食: ", menu.getDinner());
    }

    private void setMenuLine(TextView tv, String prefix, String content) {
        if (content != null && !content.trim().isEmpty() && !content.trim().equals("---")) {
            tv.setText(prefix + content.trim());
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
        }
    }

    private void startClassNotification(List<CalendarEvent> events) {
        if (!isAdded()) return;
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        long start = todayStart.getTimeInMillis();
        todayStart.add(Calendar.DAY_OF_YEAR, 1);
        long end = todayStart.getTimeInMillis();

        List<CalendarEvent> todayClasses = new ArrayList<>();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null) continue;
            long t = e.getDtstart().getTime();
            if (t >= start && t < end) todayClasses.add(e);
        }

        if (!todayClasses.isEmpty()) {
            ClassNotificationService.updateEvents(requireContext(), todayClasses);
        }
    }

    private void showError(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
