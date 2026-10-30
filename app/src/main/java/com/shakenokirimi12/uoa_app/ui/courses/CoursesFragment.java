package com.shakenokirimi12.uoa_app.ui.courses;

import com.shakenokirimi12.uoa_app.services.AuthorizationContext;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.textfield.TextInputEditText;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.ui.util.FetchStatus;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.CourseAdapter;

import androidx.navigation.Navigation;

import java.util.List;

public class CoursesFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerCourses;
    private TextView textEmpty;
    private View fetchNotice;
    /** 直近の取得が失敗した (キャッシュは出したまま帯で知らせる)。 */
    private boolean failed;
    private final CourseAdapter courseAdapter = new CourseAdapter();
    private final MoodleService moodleService = new MoodleService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_courses, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        recyclerCourses = view.findViewById(R.id.recycler_courses);
        recyclerCourses.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerCourses.setAdapter(courseAdapter);
        textEmpty = view.findViewById(R.id.text_empty);
        fetchNotice = view.findViewById(R.id.fetch_notice);
        courseAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }
        });

        TextInputEditText editSearch = view.findViewById(R.id.edit_search);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                courseAdapter.setQuery(s.toString());
            }
        });

        courseAdapter.setOnCourseClickListener(course -> {
            Bundle args = new Bundle();
            args.putInt("course_id", course.getId());
            args.putString("course_name", course.getFullname());
            args.putString("course_shortname", course.getShortname());
            Navigation.findNavController(view).navigate(R.id.action_courses_to_detail, args);
        });

        swipeRefresh.setColorSchemeColors(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(() -> loadCourses(true));

        List<MoodleCourse> cached = DataCache.getInstance(requireContext()).loadCourses();
        if (!cached.isEmpty()) {
            courseAdapter.setItems(cached);
        }
        // The observer only fires on adapter changes; with no cache nothing changes yet.
        updateEmptyState();

        loadCourses(false);
    }

    /** 一度も取れていないのに「コースがありません」とは言わない。取得中は何も出さない。 */
    private void updateEmptyState() {
        if (textEmpty == null) return;
        FetchStatus.Empty kind = FetchStatus.emptyKind(
                !PreferenceManager.getInstance(requireContext()).getUsername().isEmpty(), failed,
                DataCache.getInstance(requireContext()).fetchedAt(DataCache.Dataset.COURSES));
        if (kind == FetchStatus.Empty.NOT_LOGGED_IN) textEmpty.setText(R.string.not_logged_in_empty);
        else if (kind == FetchStatus.Empty.UNAVAILABLE) textEmpty.setText("コースを取得できませんでした");
        else if (kind == FetchStatus.Empty.NONE) {
            textEmpty.setText(courseAdapter.isFilteredOut() ? "検索に一致するコースがありません" : "コースがありません");
        }
        boolean empty = courseAdapter.getItemCount() == 0;
        textEmpty.setVisibility(empty && kind != FetchStatus.Empty.LOADING ? View.VISIBLE : View.GONE);
    }

    /** @param force true for pull-to-refresh; false skips the fetch while the cache is fresh. */
    private void loadCourses(boolean force) {
        if (!force && DataCache.getInstance(requireContext()).isFresh(DataCache.Dataset.COURSES, DataCache.DEFAULT_MAX_AGE_MS)) {
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
            // 取りに行かなかったのも「取れていない」。ホーム・カレンダーと同じく帯と空表示で知らせる。
            showFetchFailure();
            return;
        }
        // 引っ張って更新は利用者のやり直し。直前のログインの失敗による待ちを解く (画面を開いただけでは解かない)。
        if (force) MoodleService.allowLoginRetry();
        moodleService.ensureLoggedIn(user, pass, AuthorizationContext.INTERACTIVE, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (!isAdded()) return;
                moodleService.fetchCourses(AuthorizationContext.INTERACTIVE, new ServiceCallback<List<MoodleCourse>>() {
                    @Override
                    public void onSuccess(List<MoodleCourse> courses) {
                        if (!isAdded()) return;
                        // 先に保存する。空表示の判定は保存時刻 (fetchedAt) を見るので、後だと空の一覧が「取得中」扱いになる。
                        DataCache.getInstance(requireContext()).saveCourses(courses);
                        failed = false;
                        FetchStatus.hide(fetchNotice);
                        courseAdapter.setItems(courses);
                        updateEmptyState();
                        swipeRefresh.setRefreshing(false);
                    }

                    @Override
                    public void onError(String message) {
                        swipeRefresh.setRefreshing(false);
                        if (isAdded()) showFetchFailure();
                    }
                });
            }

            @Override
            public void onError(String message) {
                // requireContext() は detach 後に投げるので、isAdded の内側で呼ぶ
                if (isAdded()) MoodleService.markInvalidIfCredentialsError(requireContext(), message);
                swipeRefresh.setRefreshing(false);
                if (!isAdded()) return;
                showFetchFailure();
                if (com.shakenokirimi12.uoa_app.services.AuthErrors.isInvalidCredentials(message)) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** キャッシュは出したまま、いつの情報かを帯で示す。 */
    private void showFetchFailure() {
        failed = true;
        FetchStatus.showFailure(fetchNotice, DataCache.getInstance(requireContext()).fetchedAt(DataCache.Dataset.COURSES));
        updateEmptyState();
    }
}
