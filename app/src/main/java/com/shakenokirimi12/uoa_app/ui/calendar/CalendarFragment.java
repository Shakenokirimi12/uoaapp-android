package com.shakenokirimi12.uoa_app.ui.calendar;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CalendarView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.ClassDetailDialog;
import com.shakenokirimi12.uoa_app.ui.adapters.AssignmentAdapter;
import com.shakenokirimi12.uoa_app.ui.adapters.ClassAdapter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class CalendarFragment extends Fragment {

    private CalendarView calendarView;
    private TabLayout tabLayout;
    private RecyclerView recyclerEvents;
    private final Calendar selectedDate = Calendar.getInstance();

    private List<CalendarEvent> allEvents = new ArrayList<>();
    private List<Assignment> allAssignments = new ArrayList<>();
    private final ClassAdapter classAdapter = new ClassAdapter();
    private final AssignmentAdapter assignmentAdapter = new AssignmentAdapter();

    private final CampusSquareService csService = new CampusSquareService();
    private final MoodleService moodleService = new MoodleService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_calendar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        calendarView = view.findViewById(R.id.calendar_view);
        tabLayout = view.findViewById(R.id.tab_layout);
        recyclerEvents = view.findViewById(R.id.recycler_events);
        recyclerEvents.setLayoutManager(new LinearLayoutManager(requireContext()));

        classAdapter.setOnClassClickListener(cls ->
                ClassDetailDialog.show(requireContext(), cls));

        calendarView.setOnDateChangeListener((cv, year, month, dayOfMonth) -> {
            selectedDate.set(year, month, dayOfMonth);
            updateList();
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) { updateList(); }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        DataCache cache = DataCache.getInstance(requireContext());
        allEvents = cache.loadEvents();
        allAssignments = cache.loadAssignments();
        if (!allEvents.isEmpty() || !allAssignments.isEmpty()) {
            updateList();
        }

        loadData();
    }

    private void loadData() {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();
        if (user.isEmpty()) return;

        csService.fetchCalendarEvents(user, pass, new ServiceCallback<List<CalendarEvent>>() {
            @Override
            public void onSuccess(List<CalendarEvent> events) {
                if (!isAdded()) return;
                allEvents = events;
                updateList();
            }
            @Override
            public void onError(String message) {
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
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
                        updateList();
                    }
                    @Override
                    public void onError(String msg) {}
                });
            }
            @Override
            public void onError(String msg) {}
        });
    }

    private void updateList() {
        int tab = tabLayout.getSelectedTabPosition();

        selectedDate.set(Calendar.HOUR_OF_DAY, 0);
        selectedDate.set(Calendar.MINUTE, 0);
        selectedDate.set(Calendar.SECOND, 0);
        selectedDate.set(Calendar.MILLISECOND, 0);
        long dayStart = selectedDate.getTimeInMillis();
        selectedDate.add(Calendar.DAY_OF_YEAR, 1);
        long dayEnd = selectedDate.getTimeInMillis();
        selectedDate.add(Calendar.DAY_OF_YEAR, -1);

        if (tab == 0) {
            List<CalendarEvent> dayEvents = new ArrayList<>();
            for (CalendarEvent e : allEvents) {
                if (e.getDtstart() == null) continue;
                long t = e.getDtstart().getTime();
                if (t >= dayStart && t < dayEnd) dayEvents.add(e);
            }
            List<GroupedClass> grouped = GroupedClass.groupBySubject(dayEvents);
            recyclerEvents.setAdapter(classAdapter);
            classAdapter.setItems(grouped);
        } else {
            List<Assignment> dayAssignments = new ArrayList<>();
            for (Assignment a : allAssignments) {
                long t = a.getDueDate() * 1000;
                if (t >= dayStart && t < dayEnd) dayAssignments.add(a);
            }
            recyclerEvents.setAdapter(assignmentAdapter);
            assignmentAdapter.setItems(dayAssignments);
        }
    }
}
