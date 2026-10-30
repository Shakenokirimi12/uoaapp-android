package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
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
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareFeature;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareLink;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;
import com.shakenokirimi12.uoa_app.databinding.FragmentTimetableFriendsBinding;
import com.shakenokirimi12.uoa_app.databinding.ItemTimetableFriendBinding;

import java.util.List;

/** 時間割共有の入口 (iOS の FriendsTimetableView)。受け取った友達の一覧と、交換の始め方。 */
public class FriendsTimetableFragment extends Fragment {
    private FragmentTimetableFriendsBinding binding;

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
        // フラグが off の間は新しい交換を始めさせない (受け取った時間割を見るのは続けられる)。
        // 受け取りの画面から「保存した時間割を見る」で来たときもここを通る。
        boolean enabled = TimetableShareFeature.isEnabled(
                AppConfigService.getInstance().flagValue(TimetableShareFeature.FLAG_KEY));
        binding.buttonExchange.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.buttonPaste.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 交換の画面や受け取りの画面から戻ったときに、増えた友達を出す。
        render();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void render() {
        List<TimetableFriend> friends = TimetableShareStore.getInstance(requireContext()).friends();
        binding.listFriends.removeAllViews();
        binding.textEmpty.setVisibility(friends.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (TimetableFriend f : friends) {
            ItemTimetableFriendBinding row = ItemTimetableFriendBinding.inflate(inflater, binding.listFriends, false);
            row.textAvatar.setText(f.name.isEmpty() ? "" : f.name.substring(0, f.name.offsetByCodePoints(0, 1)));
            row.textName.setText(f.name);
            row.textDetail.setText(TimetableDates.receivedLine(requireContext(), f.receivedAt, f.timetable.classes.size()));
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
