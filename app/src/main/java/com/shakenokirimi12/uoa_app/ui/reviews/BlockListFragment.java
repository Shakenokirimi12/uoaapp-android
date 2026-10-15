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
import com.shakenokirimi12.uoa_app.data.models.ReviewBlock;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.List;

/**
 * ブロック中のユーザー一覧と解除 (iOS の BlockListView)。
 * 相手の投稿者 ID はサーバーが返さない (資格情報として悪用されていたため) ので、ブロックした日だけを出す。
 */
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
        // 授業評価を使ったことが無ければブロックも無い。ここで登録まで走らせてアカウントを作らないよう、先に返す
        if (PreferenceManager.getInstance(requireContext()).getReviewUserId().isEmpty()) {
            render(java.util.Collections.emptyList());
            return;
        }
        reviewService.fetchBlocks(new ServiceCallback<List<ReviewBlock>>() {
            @Override
            public void onSuccess(List<ReviewBlock> blocks) {
                if (isAdded() && getView() != null) render(blocks);
            }

            @Override
            public void onError(String message) {
                if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void render(List<ReviewBlock> blocks) {
        list.removeAllViews();
        empty.setVisibility(blocks.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (ReviewBlock block : blocks) {
            View row = inflater.inflate(R.layout.item_blocked_user, list, false);
            TextView date = row.findViewById(R.id.text_blocked_at);
            String formatted = block.getFormattedDate();
            date.setText(formatted.isEmpty() ? "" : formatted + " にブロック");
            date.setVisibility(formatted.isEmpty() ? View.GONE : View.VISIBLE);
            ((MaterialButton) row.findViewById(R.id.button_unblock)).setOnClickListener(v -> unblock(block.getId()));
            list.addView(row);
        }
    }

    private void unblock(String blockId) {
        reviewService.unblock(blockId, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isAdded() && getView() != null) load();
            }

            @Override
            public void onError(String message) {
                if (isAdded()) Toast.makeText(requireContext(), "ブロックを解除できませんでした: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
