package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.ReviewCourse;

import java.util.ArrayList;
import java.util.List;

public class ReviewCourseAdapter extends RecyclerView.Adapter<ReviewCourseAdapter.ViewHolder> {
    private List<ReviewCourse> items = new ArrayList<>();
    private OnCourseClickListener listener;

    public interface OnCourseClickListener {
        void onCourseClick(ReviewCourse course);
    }

    public void setOnCourseClickListener(OnCourseClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<ReviewCourse> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_review_course, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReviewCourse c = items.get(position);
        holder.textCourseName.setText(c.getCourseName());
        holder.textInstructor.setText(c.getInstructor() != null ? c.getInstructor() : "");
        holder.textRating.setText(c.getAvgRatingStars());
        holder.textReviewCount.setText("(" + c.getReviewCount() + "件)");
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCourseClick(c);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textCourseName, textInstructor, textRating, textReviewCount;

        ViewHolder(View v) {
            super(v);
            textCourseName = v.findViewById(R.id.text_course_name);
            textInstructor = v.findViewById(R.id.text_instructor);
            textRating = v.findViewById(R.id.text_rating);
            textReviewCount = v.findViewById(R.id.text_review_count);
        }
    }
}
