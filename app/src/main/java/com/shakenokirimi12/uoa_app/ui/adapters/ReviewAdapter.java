package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.Review;

import java.util.ArrayList;
import java.util.List;

public class ReviewAdapter extends RecyclerView.Adapter<ReviewAdapter.ViewHolder> {
    private List<Review> items = new ArrayList<>();
    private OnMenuClickListener menuListener;

    public interface OnMenuClickListener {
        void onMenuClick(Review review, View anchor);
    }

    public void setOnMenuClickListener(OnMenuClickListener listener) {
        this.menuListener = listener;
    }

    public void setItems(List<Review> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_review, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Review r = items.get(position);
        holder.textRating.setText(r.getRatingStars());
        holder.textYear.setText(r.getEnrollmentYear() + "年度入学");

        if (r.getTitle() != null && !r.getTitle().isEmpty()) {
            holder.textTitle.setText(r.getTitle());
            holder.textTitle.setVisibility(View.VISIBLE);
        } else {
            holder.textTitle.setVisibility(View.GONE);
        }

        holder.textBody.setText(r.getBody());

        String date = r.getCreatedAt();
        if (date != null && date.length() >= 10) {
            holder.textDate.setText(date.substring(0, 10));
        }

        holder.buttonMenu.setOnClickListener(v -> {
            if (menuListener != null) menuListener.onMenuClick(r, v);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textRating, textYear, textTitle, textBody, textDate;
        final ImageView buttonMenu;

        ViewHolder(View v) {
            super(v);
            textRating = v.findViewById(R.id.text_rating);
            textYear = v.findViewById(R.id.text_year);
            textTitle = v.findViewById(R.id.text_title);
            textBody = v.findViewById(R.id.text_body);
            textDate = v.findViewById(R.id.text_date);
            buttonMenu = v.findViewById(R.id.button_menu);
        }
    }
}
