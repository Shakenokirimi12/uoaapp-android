package com.shakenokirimi12.uoa_app.ui.reviews;

import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.Review;
import com.shakenokirimi12.uoa_app.services.ReviewService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

/**
 * レビュー1件のメニュー (iOS の CourseReviewsView / InstructorReviewsView の confirmationDialog)。
 * 授業と教員の両画面で同じ振る舞いにするためにまとめている。
 *
 * 自分の投稿かどうかはサーバーが返す is_mine だけで決める。投稿者 ID は公開されなくなったので、
 * 端末側で照合する手段は無い。自分の投稿には編集・削除、他人の投稿には報告・ブロックを出す。
 * 結果は成功・失敗とも必ず知らせる (以前は失敗しても成功したように見えることがあった)。
 */
final class ReviewActionMenu {

    interface Host {
        /** 一覧を読み直す。ブロック・削除の後に呼ぶ。 */
        void reloadReviews();

        /** 自分のレビューの編集画面を開く。 */
        void openEditor(Review review);
    }

    private static final int ITEM_REPORT = 1;
    private static final int ITEM_BLOCK = 2;
    private static final int ITEM_EDIT = 3;
    private static final int ITEM_DELETE = 4;

    private ReviewActionMenu() {}

    static <F extends Fragment & Host> void show(F fragment, ReviewService service, Review review, View anchor) {
        PopupMenu popup = new PopupMenu(fragment.requireContext(), anchor);
        if (review.isMine()) {
            popup.getMenu().add(0, ITEM_EDIT, 0, "編集する");
            popup.getMenu().add(0, ITEM_DELETE, 1, "削除する");
        } else {
            popup.getMenu().add(0, ITEM_REPORT, 0, "不適切な投稿を報告する");
            popup.getMenu().add(0, ITEM_BLOCK, 1, "このユーザーをブロック");
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case ITEM_REPORT: showReportDialog(fragment, service, review); return true;
                case ITEM_BLOCK: confirmBlock(fragment, service, review); return true;
                case ITEM_EDIT: fragment.openEditor(review); return true;
                case ITEM_DELETE: confirmDelete(fragment, service, review); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private static <F extends Fragment & Host> void showReportDialog(F fragment, ReviewService service, Review review) {
        String[] reasons = {"不適切な内容", "スパム", "ハラスメント", "その他"};
        String[] reasonKeys = {"inappropriate", "spam", "harassment", "other"};
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("不適切な投稿を報告する")
                .setItems(reasons, (d, which) ->
                        service.reportReview(review.getId(), reasonKeys[which], new ServiceCallback<Boolean>() {
                            @Override public void onSuccess(Boolean r) {
                                toast(fragment, "報告しました。内容を確認します。");
                            }
                            @Override public void onError(String msg) {
                                toast(fragment, "報告できませんでした: " + msg);
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static <F extends Fragment & Host> void confirmBlock(F fragment, ReviewService service, Review review) {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("ブロック")
                .setMessage("このユーザーの投稿を非表示にしますか？")
                .setPositiveButton("ブロック", (d, w) ->
                        service.blockAuthor(review.getId(), new ServiceCallback<Boolean>() {
                            @Override public void onSuccess(Boolean r) {
                                toast(fragment, "ブロックしました");
                                if (fragment.isAdded()) fragment.reloadReviews();
                            }
                            @Override public void onError(String msg) {
                                toast(fragment, "ブロックできませんでした: " + msg);
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static <F extends Fragment & Host> void confirmDelete(F fragment, ReviewService service, Review review) {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("レビューを削除")
                .setMessage("このレビューを削除しますか？この操作は取り消せません。")
                .setPositiveButton("削除", (d, w) ->
                        service.deleteReview(review.getId(), new ServiceCallback<Boolean>() {
                            @Override public void onSuccess(Boolean r) {
                                toast(fragment, "削除しました");
                                if (fragment.isAdded()) fragment.reloadReviews();
                            }
                            @Override public void onError(String msg) {
                                toast(fragment, "削除できませんでした: " + msg);
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void toast(Fragment fragment, String message) {
        if (fragment.isAdded()) Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show();
    }
}
