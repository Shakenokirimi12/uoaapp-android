package com.shakenokirimi12.uoa_app.ui.reviews;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.ReviewAdapter;

import java.util.ArrayList;
import java.util.List;

public class CourseReviewsFragment extends Fragment implements ReviewActionMenu.Host {

    private String courseId;
    private String courseName;
    private SwipeRefreshLayout swipeRefresh;
    private ReviewAdapter adapter;
    private MaterialButton buttonWrite;
    /** 読み込んだ一覧の中の自分のレビュー。あれば「レビューを書く」を編集に切り替える (1授業1件のため)。 */
    @Nullable private Review myReview;
    /**
     * 最後に始めた読み込みの番号。引っ張って更新した直後にブロックの読み直しが重なったとき、
     * 先に始めた古い結果で新しい一覧を上書きしないために使う。
     */
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
            courseName = getArguments().getString("course_name", "");
        }

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());

        TextView textCourseName = view.findViewById(R.id.text_course_name);
        TextView textAvgRating = view.findViewById(R.id.text_avg_rating);
        TextView textReviewCount = view.findViewById(R.id.text_review_count);

        textCourseName.setText(courseName);

        // The summary is derived from the reviews actually loaded (see loadReviews), not from
        // navigation args: those were typed float/double inconsistently and went stale after posting.
        textAvgRating.setText("");
        textReviewCount.setText("");

        // Hide instructor tab - this screen is course-only
        view.findViewById(R.id.tab_review_type).setVisibility(View.GONE);

        view.findViewById(R.id.button_syllabus).setOnClickListener(v -> {
            String url = getArguments() != null ? getArguments().getString("syllabus_url", "") : "";
            if (url != null && !url.isEmpty()) {
                new CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url));
            }
        });

        buttonWrite = view.findViewById(R.id.button_write_review);
        // 画面を作り直したときは前回の判定を持ち越さない (読み込みが終わるまでは新規投稿として扱う)
        myReview = null;
        buttonWrite.setOnClickListener(v -> {
            if (myReview != null) {
                openEditor(myReview);
                return;
            }
            // 授業評価のアカウント (トークン) は投稿時に用意される。必要なのは学内システムのログインだけ。
            if (prefs.getUsername().isEmpty()) {
                Toast.makeText(requireContext(), "ログインが必要です", Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle args = new Bundle();
            args.putString("course_id", courseId);
            args.putString("course_name", courseName);
            args.putString("review_type", "course");
            Navigation.findNavController(v).navigate(R.id.action_course_reviews_to_write, args);
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
        // 担当教員はページの値ではなくレビュー自身の欄を渡す (iOS と同じ)
        Navigation.findNavController(v).navigate(R.id.action_course_reviews_to_write,
                WriteReviewFragment.editArgs(review, courseId, courseName));
    }

    private void loadReviews() {
        int generation = ++loadGeneration;
        swipeRefresh.setRefreshing(true);
        reviewService.getReviews(courseId, new ServiceCallback<List<Review>>() {
            @Override
            public void onSuccess(List<Review> reviews) {
                // 画面が作り直された (投稿画面から戻った等) か、後から始めた読み込みがあれば捨てる
                if (!isAdded() || getView() == null || generation != loadGeneration) return;
                // Filter to course reviews only
                List<Review> courseOnly = new ArrayList<>();
                Review mine = null;
                for (Review r : reviews) {
                    if ("instructor".equals(r.getReviewType())) continue;
                    courseOnly.add(r);
                    if (mine == null && r.isMine()) mine = r;
                }
                myReview = mine;
                buttonWrite.setText(mine != null ? "編集する" : "レビューを書く");
                adapter.setItems(courseOnly);
                updateSummary(courseOnly);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || getView() == null || generation != loadGeneration) return;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateSummary(List<Review> reviews) {
        View v = getView();
        if (v == null) return;
        TextView textAvgRating = v.findViewById(R.id.text_avg_rating);
        TextView textReviewCount = v.findViewById(R.id.text_review_count);
        if (reviews.isEmpty()) {
            textAvgRating.setText("");
            textReviewCount.setText("まだレビューがありません");
            return;
        }
        double sum = 0;
        for (Review r : reviews) sum += r.getRating();
        double avg = sum / reviews.size();
        textAvgRating.setText(starsFor(avg) + " " + String.format(java.util.Locale.JAPAN, "%.1f", avg));
        textReviewCount.setText(reviews.size() + "件のレビュー");
    }

    private String starsFor(double rating) {
        int r = (int) Math.round(rating);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < r ? "★" : "☆");
        return sb.toString();
    }
}
