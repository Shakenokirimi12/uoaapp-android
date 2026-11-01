package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayLink;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairing;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore;

import java.util.ArrayList;
import java.util.List;

/**
 * PC との連携を始める案内のページ (できること、手順 1〜3)。1 ページに 1 つのことだけを書き、スワイプで送る。
 * 初期設定 (OnboardingActivity) ではページを段として並べ、設定の「PCとの連携」ではまだ連携していないときに
 * {@link #attach} で出す。iOS の PCRelaySetupPages.swift と同じ構成 (SPEC.md 9.5)。
 */
public final class PcRelaySetupPages {
    private PcRelaySetupPages() {}

    public static final int INTRO = 0;
    public static final int INSTALL = 1;
    public static final int LINK = 2;
    public static final int SCAN = 3;
    public static final int COUNT = 4;

    /** 拡張の短縮 URL。ブラウザに合わせて Chrome ウェブストア・Firefox アドオン・案内のページに飛ぶ。 */
    private static final String EXTENSION_URL = "https://uoa-app.shakenokiri.me/ext";

    /** ページを作る。showsSkipNote は初期設定のときだけ (使わない人に飛ばし方を知らせる)。 */
    @NonNull
    public static View createPage(@NonNull ViewGroup parent, int page, boolean showsSkipNote) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.page_pc_relay_setup, parent, false);
        switch (page) {
            case INTRO:
                view.findViewById(R.id.text_pc_title).setVisibility(View.VISIBLE);
                // 手順の前に、連携すると何ができるようになるかを書く。
                showText(view, R.id.text_pc_body, R.string.otp_relay_setup_benefits);
                showText(view, R.id.text_pc_note, R.string.otp_relay_privacy);
                view.findViewById(R.id.text_pc_skip_note).setVisibility(showsSkipNote ? View.VISIBLE : View.GONE);
                break;
            case INSTALL:
                showStep(view, 1, R.string.otp_relay_setup_step1);
                View guide = view.findViewById(R.id.card_pc_ext_guide);
                guide.setVisibility(View.VISIBLE);
                setUpExtensionGuide(guide);
                break;
            case LINK:
                showStep(view, 2, R.string.otp_relay_setup_step2);
                showText(view, R.id.text_pc_note, R.string.otp_relay_setup_step2_hint);
                break;
            default:
                showStep(view, 3, R.string.otp_relay_setup_step3);
                showText(view, R.id.text_pc_note, R.string.otp_relay_setup_step3_hint);
                View paste = view.findViewById(R.id.button_pc_paste);
                paste.setVisibility(View.VISIBLE);
                paste.setOnClickListener(v -> showPasteDialog(v.getContext()));
                break;
        }
        return view;
    }

    /**
     * 連携の状態に合わせて直す。最後のページだけ、連携できたらブラウザ名を出す。
     * 連携は別の画面 (OtpRelayActivity) で増えるので、戻ってきたときにも呼ぶ。
     */
    public static void bindPage(@NonNull View view, int page) {
        if (page != SCAN) return;
        List<OtpRelayPairing> pairings = OtpRelayStore.all();
        view.findViewById(R.id.group_pc_paired).setVisibility(pairings.isEmpty() ? View.GONE : View.VISIBLE);
        List<String> names = new ArrayList<>();
        for (OtpRelayPairing p : pairings) names.add(p.browserName);
        ((TextView) view.findViewById(R.id.text_pc_paired_browsers)).setText(String.join("\n", names));
    }

    /** ページの位置の印。今のページは長い丸 (iOS の Capsule と同じ大きさ)。 */
    public static void renderDots(@NonNull LinearLayout dots, int count, int current) {
        Context ctx = dots.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int active = MaterialColors.getColor(dots, androidx.appcompat.R.attr.colorPrimary);
        int inactive = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(dots, com.google.android.material.R.attr.colorOnSurface), (int) (255 * 0.3f));
        dots.removeAllViews();
        for (int i = 0; i < count; i++) {
            boolean selected = i == current;
            View dot = new View(ctx);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(4 * density);
            shape.setColor(selected ? active : inactive);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round((selected ? 20 : 8) * density), Math.round(8 * density));
            if (i > 0) lp.setMarginStart(Math.round(8 * density));
            dots.addView(dot, lp);
        }
        dots.setContentDescription(ctx.getString(R.string.pc_relay_setup_page_position, current + 1, count));
    }

    /**
     * 「PC に拡張機能を入れる」(view_otp_relay_extension_guide)。アプリからは何も送らない。メールは利用者がメールの
     * アプリで送る (宛先は、OTP を読むのと同じ自分の大学のメール)。
     */
    public static void setUpExtensionGuide(@NonNull View guide) {
        Context ctx = guide.getContext();
        guide.findViewById(R.id.button_ext_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("UoA 非公式拡張", EXTENSION_URL));
            // Android 13 以降はシステムがコピーしたことを表示するので、重ねて出さない。
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(ctx, R.string.otp_relay_ext_copied, Toast.LENGTH_SHORT).show();
            }
        });
        View mail = guide.findViewById(R.id.button_ext_mail);
        String address = ImapOtpFetcher.mailboxAddress(PreferenceManager.getInstance(ctx).getUsername());
        // ユーザー名が無いと宛先が決まらない。宛先なしのメールは出さない。
        mail.setVisibility(address != null ? View.VISIBLE : View.GONE);
        mail.setOnClickListener(v -> {
            if (address == null) return;
            String subject = ctx.getString(R.string.otp_relay_ext_mail_subject);
            String body = ctx.getString(R.string.otp_relay_ext_mail_body, EXTENSION_URL);
            // 宛先・件名・本文は mailto: (RFC 6068) だけで渡す。extras にも入れると、両方を読んで
            // 宛先や本文を二重にするメールのアプリがある。
            Uri mailto = Uri.parse("mailto:" + Uri.encode(address, "@") + "?subject=" + Uri.encode(subject)
                    + "&body=" + Uri.encode(body));
            Intent intent = new Intent(Intent.ACTION_SENDTO, mailto);
            try {
                ctx.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(ctx, R.string.otp_relay_ext_no_mail_app, Toast.LENGTH_SHORT).show();
            }
        });
        guide.findViewById(R.id.button_ext_share).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EXTENSION_URL);
            ctx.startActivity(Intent.createChooser(send, null));
        });
    }

    /** 連携用のリンクを貼り付けて連携する。確認と登録は OtpRelayActivity がする。 */
    public static void showPasteDialog(@NonNull Context ctx) {
        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_otp_relay_paste, null);
        EditText edit = dialogView.findViewById(R.id.edit_link);
        // クリップボードに連携用のリンクが入っていれば、最初から入れておく。
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                String text = clip.getItemAt(0).getText().toString().trim();
                if (OtpRelayLink.parse(text) != null) edit.setText(text);
            }
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.otp_relay_paste_title)
                .setView(dialogView)
                .setPositiveButton("連携", (d, w) -> {
                    String link = edit.getText().toString().trim();
                    if (OtpRelayLink.parse(link) == null) {
                        new MaterialAlertDialogBuilder(ctx)
                                .setTitle("読み取れませんでした")
                                .setMessage(R.string.otp_relay_paste_invalid)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        return;
                    }
                    ctx.startActivity(OtpRelayActivity.pairIntent(ctx, link));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void showStep(@NonNull View view, int number, int titleRes) {
        view.findViewById(R.id.group_pc_step).setVisibility(View.VISIBLE);
        ((TextView) view.findViewById(R.id.text_pc_step_number)).setText(String.valueOf(number));
        ((TextView) view.findViewById(R.id.text_pc_step_title)).setText(titleRes);
    }

    private static void showText(@NonNull View view, int id, int textRes) {
        TextView text = view.findViewById(id);
        text.setText(textRes);
        text.setVisibility(View.VISIBLE);
    }
}
