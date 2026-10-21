package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayLink;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairing;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PC との連携 (OTP)。連携の一覧・解除・リンクの貼り付けによる連携。依頼にはいつも自動で返す。 */
public class OtpRelaySettingsFragment extends Fragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout container;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_otp_relay_settings, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        container = view.findViewById(R.id.container_pairings);
        view.findViewById(R.id.button_paste_link).setOnClickListener(v -> showPasteDialog());
        setUpExtensionGuide(view);
    }

    /** 拡張の短縮 URL。ブラウザに合わせて Chrome ウェブストア・Firefox アドオン・案内のページに飛ぶ。 */
    private static final String EXTENSION_URL = "https://uoa-app.shakenokiri.me/ext";

    /**
     * 「PC に拡張を入れる」。アプリからは何も送らない。メールは利用者がメールのアプリで送る
     * (宛先は、OTP を読むのと同じ自分の大学のメール)。
     */
    private void setUpExtensionGuide(View view) {
        view.findViewById(R.id.button_ext_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("UoA 非公式拡張", EXTENSION_URL));
            // Android 13 以降はシステムがコピーしたことを表示するので、重ねて出さない。
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(requireContext(), R.string.otp_relay_ext_copied, Toast.LENGTH_SHORT).show();
            }
        });
        View mail = view.findViewById(R.id.button_ext_mail);
        String address = ImapOtpFetcher.mailboxAddress(PreferenceManager.getInstance(requireContext()).getUsername());
        // ユーザー名が無いと宛先が決まらない。宛先なしのメールは出さない。
        mail.setVisibility(address != null ? View.VISIBLE : View.GONE);
        mail.setOnClickListener(v -> {
            if (address == null) return;
            String subject = getString(R.string.otp_relay_ext_mail_subject);
            String body = getString(R.string.otp_relay_ext_mail_body, EXTENSION_URL);
            // 宛先・件名・本文は mailto: (RFC 6068) だけで渡す。extras にも入れると、両方を読んで
            // 宛先や本文を二重にするメールのアプリがある。
            Uri mailto = Uri.parse("mailto:" + Uri.encode(address, "@") + "?subject=" + Uri.encode(subject)
                    + "&body=" + Uri.encode(body));
            Intent intent = new Intent(Intent.ACTION_SENDTO, mailto);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(requireContext(), R.string.otp_relay_ext_no_mail_app, Toast.LENGTH_SHORT).show();
            }
        });
        view.findViewById(R.id.button_ext_share).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EXTENSION_URL);
            startActivity(Intent.createChooser(send, null));
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 連携は別の画面 (OtpRelayActivity) で増えるので、戻ってくるたびに読み直す。
        render();
        // 同意を記録する前に作った連携があれば、ここで確かめる (iOS の OTPRelaySettingsView.onAppear と同じ)。
        OtpRelayConsentPrompt.show(requireActivity(), this::render);
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void render() {
        View root = getView();
        if (root == null) return;
        container.removeAllViews();
        List<OtpRelayPairing> pairings = OtpRelayStore.all();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        if (pairings.isEmpty()) {
            TextView empty = (TextView) inflater.inflate(R.layout.item_otp_relay_empty, container, false);
            container.addView(empty);
        }
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN);
        for (int i = 0; i < pairings.size(); i++) {
            OtpRelayPairing p = pairings.get(i);
            if (i > 0) inflater.inflate(R.layout.item_divider, container, true);
            View row = inflater.inflate(R.layout.item_otp_relay_pairing, container, false);
            ((TextView) row.findViewById(R.id.text_browser_name)).setText(p.browserName);
            ((TextView) row.findViewById(R.id.text_paired_at)).setText(
                    getString(R.string.otp_relay_paired_on, dateFmt.format(new Date(p.pairedAt))));
            // 同意を記録する前に作った連携。確認 (この画面を開いたときに出る) が済むまで送らない。
            row.findViewById(R.id.text_consent_pending).setVisibility(p.hasMailConsent() ? View.GONE : View.VISIBLE);
            row.findViewById(R.id.button_unpair).setOnClickListener(v -> confirmUnpair(p));
            container.addView(row);
        }

        OtpRelayStore.LastResult last = OtpRelayStore.lastResult();
        int vis = last != null ? View.VISIBLE : View.GONE;
        root.findViewById(R.id.header_last_result).setVisibility(vis);
        root.findViewById(R.id.card_last_result).setVisibility(vis);
        if (last != null) {
            TextView summary = root.findViewById(R.id.text_last_summary);
            summary.setText((last.succeeded ? "成功: " : "失敗: ") + last.summary);
            ((TextView) root.findViewById(R.id.text_last_when)).setText(DateUtils.getRelativeTimeSpanString(
                    last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            TextView detail = root.findViewById(R.id.text_last_detail);
            detail.setVisibility(last.detail != null && !last.detail.isEmpty() ? View.VISIBLE : View.GONE);
            detail.setText(last.detail);
        }
    }

    private void showPasteDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_otp_relay_paste, null);
        EditText edit = dialogView.findViewById(R.id.edit_link);
        // クリップボードに連携用のリンクが入っていれば、最初から入れておく。
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                String text = clip.getItemAt(0).getText().toString().trim();
                if (OtpRelayLink.parse(text) != null) edit.setText(text);
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.otp_relay_paste_title)
                .setView(dialogView)
                .setPositiveButton("連携", (d, w) -> {
                    String link = edit.getText().toString().trim();
                    if (OtpRelayLink.parse(link) == null) {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("読み取れませんでした")
                                .setMessage(R.string.otp_relay_paste_invalid)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        return;
                    }
                    startActivity(OtpRelayActivity.pairIntent(requireContext(), link));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmUnpair(OtpRelayPairing p) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.otp_relay_unpair_confirm, p.browserName))
                .setPositiveButton(R.string.otp_relay_unpair, (d, w) -> unpair(p))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void unpair(OtpRelayPairing p) {
        executor.execute(() -> {
            boolean ok;
            String error = null;
            try {
                ok = OtpRelayPairingService.unpair(p);
            } catch (IOException e) {
                ok = false;
                error = getString(R.string.otp_relay_unreachable);
            }
            boolean done = ok;
            String message = error;
            main.post(() -> {
                if (!isAdded()) return;
                if (done) {
                    render();
                    return;
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.otp_relay_unpair_failed_title)
                        .setMessage(message != null
                                ? message + "\n\n" + getString(R.string.otp_relay_unpair_failed_message)
                                : getString(R.string.otp_relay_unpair_failed_message))
                        .setPositiveButton(R.string.otp_relay_forget_local, (d, w) -> {
                            OtpRelayStore.remove(p.pairingId);
                            render();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }
}
