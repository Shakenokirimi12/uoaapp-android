package com.shakenokirimi12.uoa_app.ui.idp;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.ui.timetable.QrCodes;

/**
 * 認証アプリへキーを渡す方法 (iOS の AuthenticatorAppMenu 相当): otpauth:// を受けるアプリを一覧から選んで開く、
 * キーをコピーする、QR コードを出す。
 *
 * アプリ別のボタンは作らない。2026-10-25 に調べた限り、Google Authenticator・Bitwarden などは otpauth:// を
 * 受けるだけで、アプリ別にキーを渡す URL は文書に無い。Authy と Bitwarden Authenticator は otpauth:// を受けない
 * ので、キーのコピーか、別の端末で QR を読んでもらう。
 *
 * キー (otpauth URL を含む) はログに出さない。
 */
public final class AuthenticatorKeyActions {
    private AuthenticatorKeyActions() {}

    /**
     * otpauth:// を受けるアプリがあるか。キーの入っていない URL で調べる。Android 11 以降は manifest の
     * {@code <queries>} が無いと、入っていても見えない。
     */
    public static boolean canOpen(@NonNull Context context) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("otpauth://totp/probe"));
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return !pm.queryIntentActivities(probe,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)).isEmpty();
        }
        return !pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    /** システムの一覧から開くアプリを選んでもらう。既定のアプリが決まっていても毎回一覧を出す (ACTION_CHOOSER)。 */
    public static void open(@NonNull Fragment fragment, @NonNull String otpauthUrl) {
        // ダイアログは Fragment に結び付いていないので、押された時点で画面が外れていることがある。
        if (!fragment.isAdded()) return;
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(otpauthUrl));
        try {
            fragment.startActivity(Intent.createChooser(view, fragment.getString(R.string.otp_choose_authenticator)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(fragment.requireContext(), R.string.otp_no_otpauth_app, Toast.LENGTH_LONG).show();
        }
    }

    /** コピーしたキーを置いておく時間 (iOS の AuthenticatorKeyView.copyToPasteboard と同じ 2 分)。 */
    static final long CLIP_TTL_MS = 2 * 60 * 1000L;
    private static final String CLIP_LABEL = "otp secret";
    /** コピーしたキーを消す期限 (SystemClock.elapsedRealtime)。0 なら消すものは無い。 */
    private static long clipExpiresAt;
    /** コピーしたクリップの ClipDescription.getTimestamp()。読めなかったら -1 (ラベルで見分ける)。 */
    private static long clipTimestamp = -1;

    /** 期限が来たクリップをどうするか。 */
    enum ClipAction { NONE, WAIT, CLEAR, FORGET }

    /**
     * @param ours 今のクリップがこのアプリのコピーしたキーか。null は読めない (Android 10 以降、前面に
     *             いないとクリップボードは読めない)。読めないうちは消さずに待つ (別のアプリでコピーした
     *             ものを消さないため)。
     */
    static ClipAction clipAction(long now, long expiresAt, @Nullable Boolean ours) {
        if (expiresAt == 0) return ClipAction.NONE;
        if (now < expiresAt || ours == null) return ClipAction.WAIT;
        return ours ? ClipAction.CLEAR : ClipAction.FORGET;
    }

    /**
     * キーをクリップボードへ。Android 13 以降は機密として扱わせ、プレビューに出さない。2 分たったら、まだ
     * このキーが入っていれば消す (前面にいなくて確かめられなければ、次に前面に戻ったとき: MainActivity)。
     * 長押しのコピーもここを通す (キーの TextView は選択できないようにしてある)。
     */
    public static void copySecret(@NonNull Context context, @NonNull String groupedSecret) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(CLIP_LABEL, groupedSecret.replace(" ", ""));
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        ClipDescription set = clipboard.getPrimaryClipDescription();
        clipTimestamp = set != null ? set.getTimestamp() : -1;
        clipExpiresAt = SystemClock.elapsedRealtime() + CLIP_TTL_MS;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> clearExpiredSecret(app), CLIP_TTL_MS + 500);
        Toast.makeText(context, R.string.otp_secret_copied, Toast.LENGTH_SHORT).show();
    }

    /** 期限の過ぎたキーがまだクリップボードにあれば消す。前面に戻ったとき (窓にフォーカスが来たとき) にも呼ぶ。 */
    public static void clearExpiredSecret(@NonNull Context context) {
        if (clipExpiresAt == 0) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipDescription current = clipboard.getPrimaryClipDescription();
        Boolean ours = current == null ? null
                : clipTimestamp >= 0 ? current.getTimestamp() == clipTimestamp
                : CLIP_LABEL.contentEquals(current.getLabel());
        switch (clipAction(SystemClock.elapsedRealtime(), clipExpiresAt, ours)) {
            case CLEAR:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip();
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                clipExpiresAt = 0;
                break;
            case FORGET:
                clipExpiresAt = 0;
                break;
            default:
                break;
        }
    }

    /** キーを出す窓を、スクリーンショット・画面録画・最近のアプリのサムネイルに写らないようにする。 */
    public static void setSecure(@Nullable Window window, boolean secure) {
        if (window == null) return;
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** キーを表示する TextView。選択による素のコピー (機密の印も期限も付かない) をさせず、長押しでここのコピーを使う。 */
    public static void bindSecretText(@NonNull TextView text, @NonNull java.util.function.Supplier<String> secret) {
        text.setTextIsSelectable(false);
        text.setOnLongClickListener(v -> {
            String s = secret.get();
            if (s == null) return false;
            copySecret(v.getContext(), s);
            return true;
        });
    }

    /**
     * QR コード (端末内で作る) とキーを出す。この端末では読めないが、別の端末の認証アプリで読める。
     * 「認証アプリで開く」は、開けるアプリがあるときだけ出す。
     */
    public static void showKeyDialog(@NonNull Fragment fragment, @NonNull String otpauthUrl, @NonNull String groupedSecret) {
        Context context = fragment.requireContext();
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_authenticator_key, null, false);
        ImageView image = content.findViewById(R.id.image_qr);
        int size = Math.round(200 * context.getResources().getDisplayMetrics().density);
        Bitmap qr = QrCodes.make(otpauthUrl, size);
        if (qr != null) image.setImageBitmap(qr);
        else image.setVisibility(View.GONE);
        TextView secretText = content.findViewById(R.id.text_secret);
        secretText.setText(groupedSecret);
        bindSecretText(secretText, () -> groupedSecret);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.login_secret_title)
                .setView(content)
                .setPositiveButton(R.string.otp_copy_secret, (d, w) -> copySecret(context, groupedSecret))
                .setNegativeButton(R.string.close, null);
        if (canOpen(context)) {
            builder.setNeutralButton(R.string.otp_add_to_authenticator, (d, w) -> open(fragment, otpauthUrl));
        }
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        setSecure(dialog.getWindow(), true);
        dialog.show();
    }
}
