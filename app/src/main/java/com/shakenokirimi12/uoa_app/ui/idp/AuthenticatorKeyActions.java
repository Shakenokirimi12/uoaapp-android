package com.shakenokirimi12.uoa_app.ui.idp;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

    /** キーをクリップボードへ。Android 13 以降は機密として扱わせ、プレビューに出さない。 */
    public static void copySecret(@NonNull Context context, @NonNull String groupedSecret) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("otp secret", groupedSecret.replace(" ", ""));
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.otp_secret_copied, Toast.LENGTH_SHORT).show();
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
        ((TextView) content.findViewById(R.id.text_secret)).setText(groupedSecret);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.login_secret_title)
                .setView(content)
                .setPositiveButton(R.string.otp_copy_secret, (d, w) -> copySecret(context, groupedSecret))
                .setNegativeButton(R.string.close, null);
        if (canOpen(context)) {
            builder.setNeutralButton(R.string.otp_add_to_authenticator, (d, w) -> open(fragment, otpauthUrl));
        }
        builder.show();
    }
}
