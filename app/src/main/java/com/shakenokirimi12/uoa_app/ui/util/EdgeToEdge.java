package com.shakenokirimi12.uoa_app.ui.util;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * targetSdk 35以降はエッジツーエッジが強制される。テーマの statusBarColor /
 * navigationBarColor は無視され、コンテンツがシステムバーの下まで描画される。
 * 何もしないと、画面上端がステータスバーに、下部ナビゲーションがナビゲーションバーに
 * それぞれ隠れる。
 *
 * ここではインセットをパディングとして反映する。ルートには上と左右(ランドスケープの
 * カットアウト)を、下部バーには下を当てる。下部バーの背景はそのままナビゲーションバーの
 * 裏まで伸びるので、見た目は一枚の面になる。
 */
public final class EdgeToEdge {
    private EdgeToEdge() {}

    /** テーマの背景(ライトなら明るい、ダークなら暗い)の上にバーが載る通常の画面向け。 */
    public static void apply(@NonNull Activity activity, @NonNull View root, @Nullable View bottomBar) {
        boolean isNight = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        apply(activity, root, bottomBar, isNight);
    }

    /**
     * @param darkBackground バーの裏に来る背景が暗いか。暗ければアイコンを白にする。
     *                       オンボーディングのように primary 色で塗り潰した画面は
     *                       ライトテーマでもここが true になる。
     */
    public static void apply(@NonNull Activity activity, @NonNull View root, @Nullable View bottomBar,
                             boolean darkBackground) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);

        // システムバーが透過になった分、その上に載るアイコンの色を背景に合わせる。
        // 以前はステータスバーを primary 色で塗って白アイコンにしていたが、
        // 透過後は画面ごとの背景の上に載る。
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, root);
        controller.setAppearanceLightStatusBars(!darkBackground);
        controller.setAppearanceLightNavigationBars(!darkBackground);

        final int rootLeft = root.getPaddingLeft();
        final int rootTop = root.getPaddingTop();
        final int rootRight = root.getPaddingRight();
        final int rootBottom = root.getPaddingBottom();
        final int barBottom = bottomBar != null ? bottomBar.getPaddingBottom() : 0;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // 下は下部バーがあればそちらに渡す。無ければルートで受ける。
            int bottomForRoot = bottomBar != null ? 0 : bars.bottom;
            v.setPadding(rootLeft + bars.left, rootTop + bars.top, rootRight + bars.right, rootBottom + bottomForRoot);
            if (bottomBar != null) {
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                        bottomBar.getPaddingRight(), barBottom + bars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
