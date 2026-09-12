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

import com.google.android.material.button.MaterialButton;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.List;

/** ブロック中のユーザー一覧と解除 (iOS の BlockListView)。 */
public class BlockListFragment extends Fragment {
    private final ReviewService reviewService = new ReviewService();
    private LinearLayout list;
    private TextView empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_block_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        list = view.findViewById(R.id.list_blocked);
        empty = view.findViewById(R.id.text_empty);
        load();
    }

    private void load() {
        String userId = PreferenceManager.getInstance(requireContext()).getReviewUserId();
        if (userId.isEmpty()) {
            render(java.util.Collections.emptyList());
            return;
        }
        reviewService.fetchBlockedUsers(userId, new ServiceCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> ids) {
                if (isAdded()) render(ids);
            }

            @Override
            public void onError(String message) {
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render(List<String> ids) {
        list.removeAllViews();
        empty.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (String id : ids) {
            View row = inflater.inflate(R.layout.item_blocked_user, list, false);
            // 生の ID を丸ごと出す必要は無い。先頭だけで識別できれば十分
            String shown = id.length() > 12 ? id.substring(0, 12) + "..." : id;
            ((TextView) row.findViewById(R.id.text_user_id)).setText(shown);
            ((MaterialButton) row.findViewById(R.id.button_unblock)).setOnClickListener(v -> unblock(id));
            list.addView(row);
        }
    }

    private void unblock(String targetUserId) {
        String userId = PreferenceManager.getInstance(requireContext()).getReviewUserId();
        reviewService.unblockUser(targetUserId, userId, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isAdded()) load();
            }

            @Override
            public void onError(String message) {
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
