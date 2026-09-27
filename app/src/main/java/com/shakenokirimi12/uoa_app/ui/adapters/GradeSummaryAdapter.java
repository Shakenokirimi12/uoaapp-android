package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.CreditSummary;

import java.util.Locale;

/**
 * 成績一覧の先頭に出す累積 GPA・修得単位数のカード。値は CampusSquare「単位修得状況照会」のものを
 * そのまま出す (アプリでは計算しない)。まだ取れていないときは出さない。
 */
public class GradeSummaryAdapter extends RecyclerView.Adapter<GradeSummaryAdapter.ViewHolder> {
    private CreditSummary summary;

    public void setSummary(@Nullable CreditSummary summary) {
        boolean had = getItemCount() > 0;
        this.summary = summary;
        boolean has = getItemCount() > 0;
        if (had && has) notifyItemChanged(0);
        else if (had) notifyItemRemoved(0);
        else if (has) notifyItemInserted(0);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_grade_summary, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.textGpa.setText(summary.cumulativeGpa != null
                ? String.format(Locale.US, "%.2f", summary.cumulativeGpa) : "—");
        holder.textEarned.setText(summary.earnedCredits != null ? formatCredits(summary.earnedCredits) : "—");

        // 学期ごとの GPA を「2026年度 1学期 3.76 ・ 2学期 3.00」のように年度ごとにまとめる。
        StringBuilder terms = new StringBuilder();
        String year = null;
        for (CreditSummary.TermGpa t : summary.terms) {
            if (!t.year.equals(year)) {
                if (terms.length() > 0) terms.append('\n');
                terms.append(t.year).append("年度 ");
                year = t.year;
            } else {
                terms.append(" ・ ");
            }
            terms.append(java.text.Normalizer.normalize(t.term, java.text.Normalizer.Form.NFKC))
                    .append(' ').append(String.format(Locale.US, "%.2f", t.gpa));
        }
        holder.textDetail.setText(terms);
        holder.textDetail.setVisibility(terms.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private static String formatCredits(double credits) {
        return credits == Math.rint(credits)
                ? String.valueOf((long) credits)
                : String.format(Locale.US, "%.1f", credits);
    }

    @Override
    public int getItemCount() {
        return summary != null && !summary.isEmpty() ? 1 : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textGpa;
        final TextView textEarned;
        final TextView textDetail;

        ViewHolder(View view) {
            super(view);
            textGpa = view.findViewById(R.id.text_gpa);
            textEarned = view.findViewById(R.id.text_earned_credits);
            textDetail = view.findViewById(R.id.text_summary_detail);
        }
    }
}
