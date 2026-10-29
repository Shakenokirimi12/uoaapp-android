package com.shakenokirimi12.uoa_app.ui.home;

import com.shakenokirimi12.uoa_app.services.AuthorizationContext;
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

import com.google.android.material.color.MaterialColors;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.ui.util.FetchStatus;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;
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
import com.shakenokirimi12.uoa_app.ui.adapters.MenuAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {
    /** IdP のチュートリアルから戻ったとき、キャッシュに関係なく取り直す。 */
    public static final String KEY_FORCE_SYNC = "force_sync";


    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerClasses;
    private RecyclerView recyclerAssignments;
    private TextView textNoClasses;
    private TextView textNoAssignments;
    /** 取得失敗の帯。失敗した側のデータがいつのものかを出す。 */
    private View fetchNotice;
    private boolean eventsFailed;
    private boolean assignmentsFailed;
    /** 未ログイン。「授業はありません」と言わない。 */
    private boolean notLoggedIn;
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
    private TextView textMenuHeadline;
    private LinearLayout layoutMenuRows;

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
        fetchNotice = view.findViewById(R.id.fetch_notice);
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
        textMenuHeadline = view.findViewById(R.id.text_menu_headline);
        layoutMenuRows = view.findViewById(R.id.layout_menu_rows);

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

        swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(swipeRefresh, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(() -> {
            syncData(true);
            loadCafeteriaMenu(true);
        });

        DataCache cache = DataCache.getInstance(requireContext());
        allEvents = cache.loadEvents();
        allAssignments = cache.loadAssignments();
        // 空でも描く。キャッシュが新しいと syncData は取得を飛ばして何も描かないので、ここで描かないと
        // 「今日の授業はありません」も件数も出ない白い画面になる (SyncWorker が空の一覧を保存した直後など)。
        PreferenceManager loginPrefs = PreferenceManager.getInstance(requireContext());
        notLoggedIn = loginPrefs.getUsername().isEmpty() || loginPrefs.getPassword().isEmpty();
        filterAndDisplay();

        // Every tab switch recreates this fragment; hitting CampusSquare / Moodle each time is
        // needless load on the university servers, so automatic loads honor the cache age.
        // IdP のチュートリアルで「今すぐログインする」を押して戻ってきたときだけは、キャッシュに
        // 関係なく取り直してログインさせる。
        Boolean forceSync = Navigation.findNavController(view).getCurrentBackStackEntry() != null
                ? Navigation.findNavController(view).getCurrentBackStackEntry().getSavedStateHandle().remove(KEY_FORCE_SYNC)
                : null;
        syncData(Boolean.TRUE.equals(forceSync));
        loadCafeteriaMenu(false);
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
        // 一度も取れたものが無いなら「授業はありません」とは言えない (取得中なら何も言わない)。
        DataCache cache = DataCache.getInstance(requireContext());
        FetchStatus.Empty classesEmpty = FetchStatus.emptyKind(!notLoggedIn, eventsFailed,
                cache.fetchedAt(DataCache.Dataset.EVENTS));
        FetchStatus.Empty assignmentsEmpty = FetchStatus.emptyKind(!notLoggedIn, assignmentsFailed,
                cache.fetchedAt(DataCache.Dataset.ASSIGNMENTS));
        showEmpty(textNoClasses, grouped.isEmpty(), classesEmpty, R.string.classes_unavailable, R.string.home_no_classes);
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
        showEmpty(textNoAssignments, dayAssignments.isEmpty(), assignmentsEmpty,
                R.string.assignments_unavailable, R.string.home_no_assignments);
        recyclerAssignments.setVisibility(dayAssignments.isEmpty() ? View.GONE : View.VISIBLE);

        // Update summary cards
        textClassCount.setText(classesEmpty == FetchStatus.Empty.NONE ? String.valueOf(grouped.size()) : "-");
        textAssignmentCount.setText(assignmentsEmpty == FetchStatus.Empty.NONE
                ? String.valueOf(dayAssignments.size()) : "-");
    }

    /** 空の一覧の文言。取得中 (LOADING) は何も出さない。 */
    private static void showEmpty(TextView view, boolean empty, FetchStatus.Empty kind,
                                  int unavailableRes, int noneRes) {
        switch (kind) {
            case NOT_LOGGED_IN: view.setText(R.string.not_logged_in_empty); break;
            case UNAVAILABLE: view.setText(unavailableRes); break;
            case NONE: view.setText(noneRes); break;
            default: break;
        }
        view.setVisibility(empty && kind != FetchStatus.Empty.LOADING ? View.VISIBLE : View.GONE);
    }

    /** 失敗した側のうち、古い方の最終更新時刻を出す。どちらも成功していれば消す。 */
    private void updateFetchNotice() {
        if (!eventsFailed && !assignmentsFailed) {
            FetchStatus.hide(fetchNotice);
            return;
        }
        DataCache cache = DataCache.getInstance(requireContext());
        long events = eventsFailed ? cache.fetchedAt(DataCache.Dataset.EVENTS) : Long.MAX_VALUE;
        long assignments = assignmentsFailed ? cache.fetchedAt(DataCache.Dataset.ASSIGNMENTS) : Long.MAX_VALUE;
        FetchStatus.showFailure(fetchNotice, Math.min(events, assignments));
    }

    /** Number of in-flight fetches started by syncData(); the spinner stays until it reaches 0. */
    private int pendingFetches = 0;

    private void fetchFinished() {
        if (--pendingFetches > 0) return;
        pendingFetches = 0;
        swipeRefresh.setRefreshing(false);
        syncStatusBar.setVisibility(View.GONE);
    }

    /**
     * @param force true for pull-to-refresh: always fetch. false for automatic loads: skip each
     *              dataset that was fetched (by this screen or SyncWorker) within the cache window.
     */
    private void syncData(boolean force) {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        String user = prefs.getUsername();
        String pass = prefs.getPassword();

        notLoggedIn = user.isEmpty() || pass.isEmpty();
        if (notLoggedIn) {
            swipeRefresh.setRefreshing(false);
            syncStatusBar.setVisibility(View.GONE);
            filterAndDisplay();
            return;
        }

        DataCache cache = DataCache.getInstance(requireContext());
        boolean fetchEvents = force || !cache.isFresh(DataCache.Dataset.EVENTS, DataCache.DEFAULT_MAX_AGE_MS);
        boolean fetchAssignments = force || !cache.isFresh(DataCache.Dataset.ASSIGNMENTS, DataCache.DEFAULT_MAX_AGE_MS);
        if (!fetchEvents) {
            // Only this screen starts the class notification service; keep doing so from the cache.
            startClassNotification(allEvents);
        }
        if (!fetchEvents && !fetchAssignments) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        // 進行中の表示は 1 つだけ。引っ張って更新したときは SwipeRefresh の輪、自動更新のときは
        // 上の「同期中...」帯。両方出すと輪が帯の上に重なる。
        if (force) {
            swipeRefresh.setRefreshing(true);
            syncStatusBar.setVisibility(View.GONE);
        } else {
            swipeRefresh.setRefreshing(false);
            syncStatusBar.setVisibility(View.VISIBLE);
            textSyncStatus.setText(R.string.home_sync_status);
        }

        // ID/PW 誤りが確定している間は自動でログインを試みない。画面を開くたびに
        // 誤ったパスワードで認証すると、大学側でアカウントがロックされうる。
        if (prefs.isCredentialsInvalid()) {
            swipeRefresh.setRefreshing(false);
            syncStatusBar.setVisibility(View.GONE);
            showError(MoodleService.INVALID_CREDENTIALS_MESSAGE);
            eventsFailed |= fetchEvents;
            assignmentsFailed |= fetchAssignments;
            updateFetchNotice();
            filterAndDisplay();
            return;
        }

        // += so a pull-to-refresh during an in-flight sync does not hide the spinner early.
        pendingFetches += (fetchEvents ? 1 : 0) + (fetchAssignments ? 1 : 0);

        // Moodle → CampusSquare の順に直列にする (バックグラウンドの同期と同じ)。2 本目は 1 本目が保存した IdP の SSO で
        // 入るので、OTP は多くて 1 回で済む。1 本目で OTP の入力を取り消したら、2 本目ではログインを始めない
        // (取り消した直後に、もう一度入力を求めない。SPEC.md「5. 前面ログイン」)。
        long syncStartedAt = System.currentTimeMillis();
        Runnable fetchCalendar = !fetchEvents ? null : () -> {
            if (!isAdded()) return;
            if (com.shakenokirimi12.uoa_app.services.idp.IdpLoginGate.shared().cancelledSince(syncStartedAt)) {
                eventsFailed = true;
                updateFetchNotice();
                filterAndDisplay();
                fetchFinished();
                return;
            }
            fetchCalendarEvents(user, pass);
        };

        if (fetchAssignments) moodleService.ensureLoggedIn(user, pass, AuthorizationContext.INTERACTIVE, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (!isAdded()) return;
                if (fetchCalendar != null) fetchCalendar.run();
                moodleService.fetchAssignments(AuthorizationContext.INTERACTIVE, new ServiceCallback<List<Assignment>>() {
                    @Override
                    public void onSuccess(List<Assignment> assignments) {
                        if (!isAdded()) return;
                        allAssignments = assignments;
                        assignmentsFailed = false;
                        DataCache.getInstance(requireContext()).saveAssignments(assignments);
                        updateFetchNotice();
                        filterAndDisplay();
                        fetchFinished();
                        prefs.setLastSync(System.currentTimeMillis());
                        showLastSyncTime();
                        com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.sync(requireContext(), assignments);
                        com.shakenokirimi12.uoa_app.services.notification.AssignmentCountdownNotifier.post(requireContext(), assignments);
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        fetchFinished();
                        assignmentsFailed = true;
                        updateFetchNotice();
                        filterAndDisplay();
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                MoodleService.markInvalidIfCredentialsError(requireContext(), message);
                fetchFinished();
                // ID/PW 誤りだけは直し方を伝える。それ以外は帯で足りる。
                if (com.shakenokirimi12.uoa_app.services.AuthErrors.isInvalidCredentials(message)) showError(message);
                assignmentsFailed = true;
                updateFetchNotice();
                filterAndDisplay();
                if (fetchCalendar != null) fetchCalendar.run();
            }
        });
        else if (fetchCalendar != null) fetchCalendar.run();
    }

    private void fetchCalendarEvents(String user, String pass) {
        csService.fetchCalendarEvents(user, pass, AuthorizationContext.INTERACTIVE, new ServiceCallback<List<CalendarEvent>>() {
            @Override
            public void onSuccess(List<CalendarEvent> events) {
                if (!isAdded()) return;
                allEvents = events;
                eventsFailed = false;
                DataCache.getInstance(requireContext()).saveEvents(events);
                // 取り直した時間割で、終わった授業の「欠席？（要確認）」を見る。
                com.shakenokirimi12.uoa_app.services.attendance.AutoAbsenceRunner.evaluateAsync(requireContext());
                updateFetchNotice();
                filterAndDisplay();
                startClassNotification(events);
                com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider.refresh(requireContext());
                fetchFinished();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                // 以前はここで「今日の授業はありません」を出していた。取れなかっただけなので、
                // キャッシュ (あれば) を出したまま、いつの情報かを帯で示す。
                eventsFailed = true;
                updateFetchNotice();
                filterAndDisplay();
                fetchFinished();
            }
        });
    }

    private void loadCafeteriaMenu(boolean force) {
        textMenuLoading.setVisibility(View.VISIBLE);
        layoutMenuContent.setVisibility(View.GONE);

        DataCache cache = DataCache.getInstance(requireContext());
        GakushokuMenu cached = cache.loadMenu();
        if (!force && cached != null && cache.isFresh(DataCache.Dataset.MENU, DataCache.MENU_MAX_AGE_MS)) {
            showTodayMenu(cached);
            return;
        }

        gakushokuService.fetchMenu(new ServiceCallback<GakushokuMenu>() {
            @Override
            public void onSuccess(GakushokuMenu menu) {
                if (!isAdded()) return;
                // Saving here lets the Gakushoku tab reuse it within its own cache window.
                DataCache.getInstance(requireContext()).saveMenu(menu);
                showTodayMenu(menu);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                textMenuLoading.setText("メニュー取得失敗");
            }
        });
    }

    private void showTodayMenu(GakushokuMenu menu) {
        GakushokuMenu.Day today = menu.today();
        if (today == null || !today.hasContent()) {
            textMenuLoading.setText("本日の掲載はありません");
            return;
        }
        textMenuLoading.setVisibility(View.GONE);
        layoutMenuContent.setVisibility(View.VISIBLE);

        String main = today.lunchMain();
        textMenuHeadline.setVisibility(main != null ? View.VISIBLE : View.GONE);
        layoutMenuContent.findViewById(R.id.text_menu_lunch_label).setVisibility(main != null ? View.VISIBLE : View.GONE);
        textMenuHeadline.setText(main);
        MenuAdapter.bindCategoryRows(layoutMenuRows, today);
    }

    private void startClassNotification(List<CalendarEvent> events) {
        if (!isAdded()) return;
        ClassNotificationService.startForToday(requireContext(), events);
    }

    private void showError(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
