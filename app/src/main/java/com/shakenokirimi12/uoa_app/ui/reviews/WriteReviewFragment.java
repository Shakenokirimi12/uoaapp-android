package com.shakenokirimi12.uoa_app.ui.reviews;

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

import com.google.android.material.textfield.TextInputEditText;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.Calendar;

public class WriteReviewFragment extends Fragment {

    private int selectedRating = 0;
    private final TextView[] starViews = new TextView[5];

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_write_review, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final String[] courseId = {getArguments() != null ? getArguments().getString("course_id", "") : ""};
        final String[] courseName = {getArguments() != null ? getArguments().getString("course_name", "") : ""};
        String reviewType = getArguments() != null ? getArguments().getString("review_type", "course") : "course";
        boolean needsNameInput = "instructor".equals(reviewType) && courseId[0].isEmpty();

        TextView textCourseName = view.findViewById(R.id.text_course_name);
        View layoutInstructorName = view.findViewById(R.id.layout_instructor_name);
        TextInputEditText editInstructorName = view.findViewById(R.id.edit_instructor_name);

        if (needsNameInput) {
            textCourseName.setVisibility(View.GONE);
            layoutInstructorName.setVisibility(View.VISIBLE);
        } else {
            textCourseName.setText("instructor".equals(reviewType) ? courseName[0] + " の評価" : courseName[0]);
        }

        TextInputEditText editYear = view.findViewById(R.id.edit_year);
        TextInputEditText editTitle = view.findViewById(R.id.edit_title);
        TextInputEditText editBody = view.findViewById(R.id.edit_body);

        editYear.setText(String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));

        // Star rating
        LinearLayout layoutStars = view.findViewById(R.id.layout_stars);
        for (int i = 0; i < 5; i++) {
            final int starIndex = i;
            TextView star = new TextView(requireContext());
            star.setText("☆");
            star.setTextSize(36);
            star.setTextColor(0xFFFFA000);
            star.setPadding(dp(8), 0, dp(8), 0);
            star.setOnClickListener(v -> {
                selectedRating = starIndex + 1;
                updateStars();
            });
            starViews[i] = star;
            layoutStars.addView(star);
        }

        // Submit
        view.findViewById(R.id.button_submit).setOnClickListener(v -> {
            if (selectedRating == 0) {
                Toast.makeText(requireContext(), "評価を選択してください", Toast.LENGTH_SHORT).show();
                return;
            }
            String body = editBody.getText() != null ? editBody.getText().toString().trim() : "";
            if (body.isEmpty()) {
                Toast.makeText(requireContext(), "レビュー本文を入力してください", Toast.LENGTH_SHORT).show();
                return;
            }
            String yearStr = editYear.getText() != null ? editYear.getText().toString().trim() : "";
            int year;
            try {
                year = Integer.parseInt(yearStr);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "入学年度を正しく入力してください", Toast.LENGTH_SHORT).show();
                return;
            }

            String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
            PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
            String userId = prefs.getReviewUserId();

            if (userId.isEmpty()) {
                Toast.makeText(requireContext(), "ログインが必要です", Toast.LENGTH_SHORT).show();
                return;
            }

            if (needsNameInput) {
                String instrName = editInstructorName.getText() != null ? editInstructorName.getText().toString().trim() : "";
                if (instrName.isEmpty()) {
                    Toast.makeText(requireContext(), "教員名を入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                courseName[0] = instrName;
                courseId[0] = "instructor_" + instrName.replace(" ", "_");
            }

            v.setEnabled(false);
            new ReviewService().submitReview(courseId[0], courseName[0], userId,
                    selectedRating, title, body, year, reviewType,
                    new ServiceCallback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), "レビューを投稿しました", Toast.LENGTH_SHORT).show();
                            requireActivity().onBackPressed();
                        }

                        @Override
                        public void onError(String message) {
                            if (!isAdded()) return;
                            v.setEnabled(true);
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                        }
                    });
        });
    }

    private void updateStars() {
        for (int i = 0; i < 5; i++) {
            starViews[i].setText(i < selectedRating ? "★" : "☆");
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
