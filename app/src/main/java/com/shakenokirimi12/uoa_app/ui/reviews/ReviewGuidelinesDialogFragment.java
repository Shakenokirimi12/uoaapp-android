package com.shakenokirimi12.uoa_app.ui.reviews;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * 授業評価のガイドライン。初回に同意を取る (iOS の ReviewGuidelinesView)。
 * 同意しない場合は授業評価の画面から戻る。閉じるだけで済ませないのは、
 * 誹謗中傷や個人情報の投稿を防ぐ前提として、必ず一度は読んでもらうため。
 */
public class ReviewGuidelinesDialogFragment extends DialogFragment {
    public static final String TAG = "review_guidelines";

    public interface Listener {
        void onAgreed();
        void onDisagreed();
    }

    /**
     * 親 Fragment (ReviewListFragment) が Listener を実装する。フィールドで持たせないのは、
     * プロセス再生成でこのダイアログが引数なしコンストラクタから作り直され、
     * フィールドが null に戻るため。その状態で「同意する」を押すと同意だけ記録されて
     * 一覧の初期化が呼ばれず、画面が空のまま残る。
     */
    private Listener listener() {
        androidx.fragment.app.Fragment parent = getParentFragment();
        return parent instanceof Listener ? (Listener) parent : null;
    }

    /** 既に出ていれば重ねない (未回答のまま回転すると親の onViewCreated が再び呼ぶ)。 */
    public static void showIfNeeded(@NonNull androidx.fragment.app.FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null) return;
        ReviewGuidelinesDialogFragment f = new ReviewGuidelinesDialogFragment();
        f.setCancelable(false);
        f.show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.Theme_Uoaapp_FullScreenDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_review_guidelines, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 全画面ダイアログもエッジツーエッジの対象。上下をシステムバーの分だけ空ける。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        int[] ids = {R.id.guideline_1, R.id.guideline_2, R.id.guideline_3, R.id.guideline_4, R.id.guideline_5};
        int[] titles = {R.string.review_guideline_1_title, R.string.review_guideline_2_title, R.string.review_guideline_3_title,
                R.string.review_guideline_4_title, R.string.review_guideline_5_title};
        int[] bodies = {R.string.review_guideline_1_body, R.string.review_guideline_2_body, R.string.review_guideline_3_body,
                R.string.review_guideline_4_body, R.string.review_guideline_5_body};
        for (int i = 0; i < ids.length; i++) {
            View row = view.findViewById(ids[i]);
            ((android.widget.TextView) row.findViewById(R.id.guideline_number)).setText(String.valueOf(i + 1));
            ((android.widget.TextView) row.findViewById(R.id.guideline_title)).setText(titles[i]);
            ((android.widget.TextView) row.findViewById(R.id.guideline_body)).setText(bodies[i]);
        }
        view.findViewById(R.id.button_agree).setOnClickListener(v -> {
            PreferenceManager.getInstance(requireContext()).setReviewGuidelinesAccepted(true);
            Listener l = listener();
            dismiss();
            if (l != null) l.onAgreed();
        });
        view.findViewById(R.id.button_disagree).setOnClickListener(v -> {
            Listener l = listener();
            dismiss();
            if (l != null) l.onDisagreed();
        });
    }

}
