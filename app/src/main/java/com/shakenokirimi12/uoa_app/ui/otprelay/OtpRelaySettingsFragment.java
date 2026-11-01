package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
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

/**
 * PC との連携 (OTP)。まだ連携していなければ手順、連携していれば一覧・解除・リンクの貼り付けによる連携。
 * 依頼にはいつも自動で返す。1 ページずつ送る案内 (PcRelaySetupPages) は初期設定だけで使う (iOS と同じ)。
 */
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
        view.findViewById(R.id.button_paste_link).setOnClickListener(v -> PcRelaySetupPages.showPasteDialog(requireContext()));
        view.findViewById(R.id.button_another_pc).setOnClickListener(v -> {
            showHowtoWhenPaired = true;
            render();
        });
        PcRelaySetupPages.setUpExtensionGuide(view.findViewById(R.id.card_ext_guide));
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

    /** 連携しているときに「別の PC と連携する」で手順を開いたか。 */
    private boolean showHowtoWhenPaired = false;

    private void render() {
        View root = getView();
        if (root == null) return;
        container.removeAllViews();
        List<OtpRelayPairing> pairings = OtpRelayStore.all();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        // 連携の有無で出し分ける (iOS と同じ)。まだなら手順を開いたまま出し、連携していれば一覧と結果を出し、手順は畳む。
        boolean paired = !pairings.isEmpty();
        // 解除して連携が無くなったら畳んだ状態に戻す (次に連携したときは iOS と同じく手順を畳んで出す)。
        if (!paired) showHowtoWhenPaired = false;
        root.findViewById(R.id.group_pairings).setVisibility(paired ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.group_howto).setVisibility(!paired || showHowtoWhenPaired ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.button_another_pc).setVisibility(paired && !showHowtoWhenPaired ? View.VISIBLE : View.GONE);
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
