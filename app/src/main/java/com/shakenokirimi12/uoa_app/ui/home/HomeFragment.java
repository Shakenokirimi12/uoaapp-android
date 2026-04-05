package com.shakenokirimi12.uoa_app.ui.home;

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

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.services.notification.ClassNotificationService;
import com.shakenokirimi12.uoa_app.ui.ClassDetailDialog;
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

    private ClassAdapter classAdapter;
    private AssignmentAdapter assignmentAdapter;
    private final MoodleService moodleService = new MoodleService();
    private final CampusSquareService csService = new CampusSquareService();

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

        classAdapter = new ClassAdapter();
        classAdapter.setOnClassClickListener(cls ->
                ClassDetailDialog.show(requireContext(), cls));
        assignmentAdapter = new AssignmentAdapter();
        recyclerClasses.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerAssignments.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerClasses.setAdapter(classAdapter);
        recyclerAssignments.setAdapter(assignmentAdapter);

        updateDateDisplay();

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

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::syncData);

        DataCache cache = DataCache.getInstance(requireContext());
        allEvents = cache.loadEvents();
        allAssignments = cache.loadAssignments();
        if (!allEvents.isEmpty() || !allAssignments.isEmpty()) {
            filterAndDisplay();
        }

        syncData();
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
    }

    private void syncData() {
        swipeRefresh.setRefreshing(true);
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();

        if (user.isEmpty() || pass.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            textNoClasses.setVisibility(View.VISIBLE);
            textNoAssignments.setVisibility(View.VISIBLE);
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
                        prefs.setLastSync(System.currentTimeMillis());
                        DataCache.getInstance(requireContext()).saveAssignments(assignments);
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        swipeRefresh.setRefreshing(false);
                        showError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                showError(message);
            }
        });
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
