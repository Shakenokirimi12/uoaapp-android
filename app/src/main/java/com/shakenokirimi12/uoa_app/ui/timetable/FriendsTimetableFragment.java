package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.timetable.SharedTimetable;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareLink;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;
import com.shakenokirimi12.uoa_app.databinding.FragmentTimetableFriendsBinding;
import com.shakenokirimi12.uoa_app.databinding.ItemTimetableFriendBinding;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 時間割共有の入口 (iOS の FriendsTimetableView)。受け取った友達の一覧と、交換の始め方。
 * 開いたときに継続共有を更新し (docs/timetable-share-v3.md の 10.6。バックグラウンドでは動かさない)、
 * 「今後も共有し続ける」の申し込みがあれば 1 人ずつ聞く。
 */
public class FriendsTimetableFragment extends Fragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FragmentTimetableFriendsBinding binding;
    private OfferPrompter offers;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTimetableFriendsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        binding.buttonExchange.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.navigation_timetable_exchange));
        binding.buttonPaste.setOnClickListener(v -> showPasteDialog());
        offers = new OfferPrompter(this, executor, this::render);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 交換の画面や受け取りの画面から戻ったときに、増えた友達を出す。学期で消える友達は先に消す (10.4)。
        TimetableShareStore store = TimetableShareStore.getInstance(requireContext());
        store.purgeExpiredTerms();
        render();
        offers.promptIfNeeded();
        Context app = requireContext().getApplicationContext();
        executor.execute(() -> {
            SharedTimetable mine = store.myTimetable(DataCache.getInstance(app).loadEvents(), System.currentTimeMillis());
            // もう走っていれば何もしない (同時に 2 本走らせない)。
            if (!store.refresh(mine)) return;
            main.post(() -> {
                if (binding == null) return;
                render();
                offers.promptIfNeeded();
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void render() {
        if (binding == null) return;
        List<TimetableFriend> friends = TimetableShareStore.getInstance(requireContext()).friends();
        binding.listFriends.removeAllViews();
        binding.textEmpty.setVisibility(friends.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (TimetableFriend f : friends) {
            ItemTimetableFriendBinding row = ItemTimetableFriendBinding.inflate(inflater, binding.listFriends, false);
            row.textAvatar.setText(f.name.isEmpty() ? "" : f.name.substring(0, f.name.offsetByCodePoints(0, 1)));
            row.textName.setText(f.name);
            row.textDetail.setText(getString(R.string.timetable_row_detail_format,
                    TimetableShareUi.stateLabel(requireContext(), f.state()),
                    TimetableDates.receivedLine(requireContext(), f.receivedAt, f.timetable.classes.size())));
            row.getRoot().setOnClickListener(v -> {
                Bundle args = new Bundle();
                args.putString(FriendTimetableFragment.ARG_FRIEND_ID, f.id);
                Navigation.findNavController(v).navigate(R.id.navigation_timetable_friend, args);
            });
            binding.listFriends.addView(row.getRoot());
        }
    }

    /** 送られてきたリンクを貼り付けて開く。標準のカメラで読めないとき・リンクがアプリで開かないときのため。 */
    private void showPasteDialog() {
        Context ctx = requireContext();
        TextInputLayout layout = new TextInputLayout(ctx);
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("https://uoa-app.shakenokiri.me/push/t/…");
        layout.addView(edit);
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
            String text = clip.getItemAt(0).getText().toString();
            if (TimetableShareLink.shareId(text) != null) edit.setText(text.trim());
        }
        FrameLayout container = new FrameLayout(ctx);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(layout);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.timetable_paste_link)
                .setView(container)
                .setPositiveButton(R.string.timetable_paste_open_button, (d, w) -> {
                    String text = edit.getText() != null ? edit.getText().toString() : "";
                    String id = TimetableShareLink.shareId(text);
                    if (id == null) {
                        new MaterialAlertDialogBuilder(ctx)
                                .setTitle(R.string.timetable_paste_invalid_title)
                                .setMessage(R.string.timetable_paste_invalid_message)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        return;
                    }
                    startActivity(TimetableIncomingActivity.intent(ctx, id));
                })
                .setNegativeButton(R.string.timetable_cancel, null)
                .show();
    }
}
