package com.shakenokirimi12.uoa_app.ui.reviews;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.ReviewAdapter;

import java.util.List;

public class InstructorReviewsFragment extends Fragment implements ReviewActionMenu.Host {

    private String courseId;
    private String instructorName;
    private SwipeRefreshLayout swipeRefresh;
    private ReviewAdapter adapter;
    /** 最後に始めた読み込みの番号。重なった読み込みの古い結果で一覧を上書きしないために使う。 */
    private int loadGeneration;
    private final ReviewService reviewService = new ReviewService();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_course_reviews, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            courseId = getArguments().getString("course_id", "");
            instructorName = getArguments().getString("course_name", "");
        }

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());

        TextView textCourseName = view.findViewById(R.id.text_course_name);
        textCourseName.setText(instructorName);

        view.findViewById(R.id.text_avg_rating).setVisibility(View.GONE);
        view.findViewById(R.id.text_review_count).setVisibility(View.GONE);
        view.findViewById(R.id.button_syllabus).setVisibility(View.GONE);
        view.findViewById(R.id.tab_review_type).setVisibility(View.GONE);

        view.findViewById(R.id.button_write_review).setOnClickListener(v -> {
            // 授業評価のアカウント (トークン) は投稿時に用意される。必要なのは学内システムのログインだけ。
            if (prefs.getUsername().isEmpty()) {
                Toast.makeText(requireContext(), "ログインが必要です", Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle args = new Bundle();
            args.putString("course_id", courseId);
            args.putString("course_name", instructorName);
            args.putString("review_type", "instructor");
            Navigation.findNavController(v).navigate(R.id.action_instructor_reviews_to_write, args);
        });

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        RecyclerView recycler = view.findViewById(R.id.recycler_reviews);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new ReviewAdapter();
        adapter.setOnMenuClickListener((review, anchor) ->
                ReviewActionMenu.show(this, reviewService, review, anchor));
        recycler.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::loadReviews);

        loadReviews();
    }

    @Override
    public void reloadReviews() {
        if (getView() != null) loadReviews();
    }

    @Override
    public void openEditor(Review review) {
        View v = getView();
        if (v == null) return;
        // 一覧に出るのは授業レビューなので、そのレビュー自身の授業として編集する。担当教員もページの教員名ではなく
        // レビューの欄を渡す。共同担当 ("A, B") のレビューを A のページから編集して "A" だけで上書きすると、
        // B の集計から外れてしまうため (iOS と同じ)。
        Navigation.findNavController(v).navigate(R.id.action_instructor_reviews_to_write,
                WriteReviewFragment.editArgs(review, review.getCourseId(), review.getCourseName()));
    }

    private void loadReviews() {
        int generation = ++loadGeneration;
        swipeRefresh.setRefreshing(true);
        reviewService.getReviews(courseId, new ServiceCallback<List<Review>>() {
            @Override
            public void onSuccess(List<Review> reviews) {
                if (!isAdded() || getView() == null || generation != loadGeneration) return;
                adapter.setItems(reviews);
                swipeRefresh.setRefreshing(false);
            }
            @Override
            public void onError(String message) {
                if (!isAdded() || getView() == null || generation != loadGeneration) return;
                swipeRefresh.setRefreshing(false);
                // 黙って空の一覧にすると「レビューが無い」と区別できないので、理由を出す
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
