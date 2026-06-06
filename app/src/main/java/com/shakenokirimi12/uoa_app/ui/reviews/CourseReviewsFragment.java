package com.shakenokirimi12.uoa_app.ui.reviews;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.adapters.ReviewAdapter;

import java.util.ArrayList;
import java.util.List;

public class CourseReviewsFragment extends Fragment {

    private String courseId;
    private String courseName;
    private SwipeRefreshLayout swipeRefresh;
    private ReviewAdapter adapter;
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
        String userId = prefs.getReviewUserId();

        TextView textCourseName = view.findViewById(R.id.text_course_name);
        TextView textAvgRating = view.findViewById(R.id.text_avg_rating);
        TextView textReviewCount = view.findViewById(R.id.text_review_count);

        textCourseName.setText(courseName);

        if (getArguments() != null) {
            double avg = getArguments().getDouble("avg_rating", 0);
            int count = getArguments().getInt("review_count", 0);
            textAvgRating.setText(starsFor(avg) + " " + String.format("%.1f", avg));
            textReviewCount.setText(count + "件のレビュー");
        }

        // Hide instructor tab - this screen is course-only
        view.findViewById(R.id.tab_review_type).setVisibility(View.GONE);

        view.findViewById(R.id.button_syllabus).setOnClickListener(v -> {
            String url = getArguments() != null ? getArguments().getString("syllabus_url", "") : "";
            if (url != null && !url.isEmpty()) {
                new CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url));
            }
        });

        view.findViewById(R.id.button_write_review).setOnClickListener(v -> {
            if (userId.isEmpty()) {
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
                showReviewMenu(review, anchor, userId));
        recycler.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(() -> loadReviews(userId));

        loadReviews(userId);
    }

    private void loadReviews(String userId) {
        swipeRefresh.setRefreshing(true);
        reviewService.getReviews(courseId, userId, new ServiceCallback<List<Review>>() {
            @Override
            public void onSuccess(List<Review> reviews) {
                if (!isAdded()) return;
                // Filter to course reviews only
                List<Review> courseOnly = new ArrayList<>();
                for (Review r : reviews) {
                    if (!"instructor".equals(r.getReviewType())) courseOnly.add(r);
                }
                adapter.setItems(courseOnly);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showReviewMenu(Review review, View anchor, String userId) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 1, 0, "不適切な投稿を報告する");
        popup.getMenu().add(0, 2, 0, "このユーザーをブロック");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showReportDialog(review, userId);
                return true;
            } else if (item.getItemId() == 2) {
                blockUser(review, userId);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showReportDialog(Review review, String userId) {
        String[] reasons = {"不適切な内容", "スパム", "ハラスメント", "その他"};
        String[] reasonKeys = {"inappropriate", "spam", "harassment", "other"};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("不適切な投稿を報告する")
                .setItems(reasons, (d, which) ->
                        reviewService.reportReview(review.getId(), userId, reasonKeys[which],
                                new ServiceCallback<Boolean>() {
                                    @Override public void onSuccess(Boolean r) {
                                        if (isAdded()) Toast.makeText(requireContext(), "報告しました", Toast.LENGTH_SHORT).show();
                                    }
                                    @Override public void onError(String msg) {
                                        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void blockUser(Review review, String userId) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("ブロック")
                .setMessage("このユーザーの投稿を非表示にしますか？")
                .setPositiveButton("ブロック", (d, w) ->
                        reviewService.blockUser(review.getInternalUserId(), userId,
                                new ServiceCallback<Boolean>() {
                                    @Override public void onSuccess(Boolean r) {
                                        if (isAdded()) {
                                            Toast.makeText(requireContext(), "ブロックしました", Toast.LENGTH_SHORT).show();
                                            loadReviews(userId);
                                        }
                                    }
                                    @Override public void onError(String msg) {
                                        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String starsFor(double rating) {
        int r = (int) Math.round(rating);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < r ? "★" : "☆");
        return sb.toString();
    }
}
