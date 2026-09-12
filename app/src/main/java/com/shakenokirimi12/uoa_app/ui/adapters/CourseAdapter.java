package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.ViewHolder> {
    private List<MoodleCourse> allItems = new ArrayList<>();
    private List<MoodleCourse> items = new ArrayList<>();
    private String query = "";
    private OnCourseClickListener listener;

    public interface OnCourseClickListener {
        void onCourseClick(MoodleCourse course);
    }

    public void setOnCourseClickListener(OnCourseClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<MoodleCourse> items) {
        this.allItems = items;
        applyFilter();
    }

    /** Filters the visible list by course name / shortname (case-insensitive substring). */
    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        if (query.isEmpty()) {
            items = allItems;
        } else {
            List<MoodleCourse> filtered = new ArrayList<>();
            for (MoodleCourse c : allItems) {
                if (contains(c.getDisplayname()) || contains(c.getFullname()) || contains(c.getShortname())) {
                    filtered.add(c);
                }
            }
            items = filtered;
        }
        notifyDataSetChanged();
    }

    private boolean contains(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(query);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_course, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MoodleCourse course = items.get(position);
        holder.textName.setText(course.getDisplayname());
        holder.textId.setText(course.getShortname());
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCourseClick(course);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textName;
        final TextView textId;

        ViewHolder(View view) {
            super(view);
            textName = view.findViewById(R.id.text_course_name);
            textId = view.findViewById(R.id.text_course_id);
        }
    }
}
