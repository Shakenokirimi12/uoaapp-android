package com.shakenokirimi12.uoa_app.ui.reviews;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.Calendar;

public class WriteReviewFragment extends Fragment {

    private static final String ARG_REVIEW_ID = "review_id";
    private static final String ARG_RATING = "initial_rating";
    private static final String ARG_TITLE = "initial_title";
    private static final String ARG_BODY = "initial_body";
    private static final String ARG_YEAR = "initial_year";
    private static final String ARG_SCORE = "student_score";
    private static final String ARG_GRADE = "student_grade";

    private int selectedRating = 0;
    private final ImageView[] starViews = new ImageView[5];

    /**
     * 自分のレビューを編集するときの引数 (iOS の WriteReviewView(reviewIdToEdit:...))。
     * 得点・成績はこの画面に入力欄が無いが、編集はサーバー側で全欄の置き換えになるため、
     * 元の値を持ち回って送り返す (送らないと iOS で入力した値が消える)。
     */
    static Bundle editArgs(Review review, String courseId, String courseName) {
        Bundle args = new Bundle();
        args.putString("course_id", courseId);
        args.putString("course_name", courseName);
        args.putString("review_type", review.getReviewType());
        args.putString("instructor", review.getInstructor() != null ? review.getInstructor() : "");
        args.putString(ARG_REVIEW_ID, review.getId());
        args.putInt(ARG_RATING, review.getRating());
        args.putString(ARG_TITLE, review.getTitle() != null ? review.getTitle() : "");
        args.putString(ARG_BODY, review.getBody() != null ? review.getBody() : "");
        args.putInt(ARG_YEAR, review.getEnrollmentYear());
        args.putString(ARG_SCORE, review.getStudentScore());
        args.putString(ARG_GRADE, review.getStudentGrade());
        return args;
    }

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
        final String instructor = getArguments() != null ? getArguments().getString("instructor", "") : "";
        final String reviewIdToEdit = getArguments() != null ? getArguments().getString(ARG_REVIEW_ID, "") : "";
        final boolean isEditing = !reviewIdToEdit.isEmpty();
        boolean needsNameInput = !isEditing && "instructor".equals(reviewType) && courseId[0].isEmpty();

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
        // 画面の作り直し (回転など) では入力中の値を EditText 自身が復元するので、初回だけ元の値を入れる。
        // 星の選択はフィールドなので、未選択のときだけ元の値に戻す。
        if (isEditing && selectedRating == 0) selectedRating = requireArguments().getInt(ARG_RATING, 0);
        if (isEditing && savedInstanceState == null) {
            Bundle args = requireArguments();
            editTitle.setText(args.getString(ARG_TITLE, ""));
            editBody.setText(args.getString(ARG_BODY, ""));
            int year = args.getInt(ARG_YEAR, 0);
            if (year > 0) editYear.setText(String.valueOf(year));
        }
        if (isEditing) ((MaterialButton) view.findViewById(R.id.button_submit)).setText("更新する");

        // Star rating
        LinearLayout layoutStars = view.findViewById(R.id.layout_stars);
        for (int i = 0; i < 5; i++) {
            final int starIndex = i;
            ImageView star = new ImageView(requireContext());
            star.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            star.setPadding(dp(4), dp(4), dp(4), dp(4));
            star.setImageResource(R.drawable.ic_review);
            star.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.rating_star));
            star.setContentDescription((starIndex + 1) + "つ星");
            star.setOnClickListener(v -> {
                selectedRating = starIndex + 1;
                updateStars();
            });
            starViews[i] = star;
            layoutStars.addView(star);
        }
        updateStars();

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

            // 授業評価のアカウント (トークン) は送信時に用意される。必要なのは学内システムのログインだけ。
            if (prefs.getUsername().isEmpty()) {
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
            ReviewService service = new ReviewService();
            ServiceCallback<Boolean> submitCallback = new ServiceCallback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), isEditing ? "レビューを更新しました" : "レビューを投稿しました",
                                    Toast.LENGTH_SHORT).show();
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }

                        @Override
                        public void onError(String message) {
                            if (!isAdded()) return;
                            v.setEnabled(true);
                            // 409 (投稿済み・非表示) や 429 の理由はサーバーの文言をそのまま出す
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                        }
                    };
            if (isEditing) {
                Bundle args = requireArguments();
                service.editReview(reviewIdToEdit, selectedRating, title, body, year, instructor,
                        args.getString(ARG_SCORE, ""), args.getString(ARG_GRADE, ""), submitCallback);
                return;
            }
            // 一覧 (/api/courses) に出すための科目行は、サーバーが投稿と同時に作る (iOS も upsert を呼ばない)。
            // 以前のように先に upsert を投げると、その 401 でトークンを捨てた直後に投稿側が黙って
            // 新しいアカウントを登録してしまう。
            service.submitReview(courseId[0], courseName[0],
                    selectedRating, title, body, year, reviewType, instructor, submitCallback);
        });
    }

    private void updateStars() {
        for (int i = 0; i < 5; i++) {
            starViews[i].setImageResource(i < selectedRating ? R.drawable.ic_star_filled : R.drawable.ic_review);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
