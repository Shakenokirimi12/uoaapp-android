package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.timetable.OutgoingShare;
import com.shakenokirimi12.uoa_app.data.timetable.SharedTimetable;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareLink;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;
import com.shakenokirimi12.uoa_app.databinding.FragmentTimetableExchangeBinding;
import com.shakenokirimi12.uoa_app.services.TimetableShareApi;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自分の QR コードを出して、友達に読み取ってもらう画面 (iOS の TimetableExchangeView)。
 * 友達のアプリは、こちらの時間割を保存すると同時に自分の時間割を送り返すので、1 回読み取ってもらうだけで交換が済む。
 * 返事は、この画面を開いている間だけ 3 秒ごとに見に行く。
 * 返事に「今後も共有し続ける」の申し込みがあれば、ここで 1 人ずつ聞く (OfferPrompter)。
 */
public class TimetableExchangeFragment extends Fragment {
    private static final long POLL_MS = 3_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FragmentTimetableExchangeBinding binding;
    private TimetableShareStore store;
    @Nullable private OutgoingShare showing;
    private boolean polling;
    private boolean pollInFlight;
    private OfferPrompter offers;

    private final Runnable poll = this::pollOnce;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTimetableExchangeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        store = TimetableShareStore.getInstance(requireContext());
        offers = new OfferPrompter(this, executor, () -> {});
        binding.buttonShowQr.setOnClickListener(v -> {
            store.setMyName(text(binding.editName));
            create(true);
        });
        binding.buttonChangeName.setOnClickListener(v -> showNameEntry());
        binding.buttonRetry.setOnClickListener(v -> create(true));
        binding.buttonSendLink.setOnClickListener(v -> {
            if (showing == null) return;
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, getString(R.string.timetable_share_message) + "\n"
                            + TimetableShareLink.webUrl(showing.shareId));
            startActivity(Intent.createChooser(send, getString(R.string.timetable_send_link)));
        });
        binding.buttonStop.setOnClickListener(v -> {
            stopPolling();
            binding.buttonStop.setEnabled(false);
            binding.textStopError.setVisibility(View.GONE);
            executor.execute(() -> {
                boolean stopped = store.stopSharing();
                main.post(() -> {
                    if (binding == null) return;
                    if (stopped) {
                        Navigation.findNavController(binding.getRoot()).popBackStack();
                        return;
                    }
                    // 止められなかったときは閉じない。手元の共有は残っているので、もう一度押せる。
                    binding.buttonStop.setEnabled(true);
                    binding.textStopError.setVisibility(View.VISIBLE);
                    if (isResumed()) startPolling();
                });
            });
        });

        if (myTimetable() == null) {
            show(binding.groupUnavailable);
        } else if (store.myName().isEmpty() && store.outgoing() == null) {
            showNameEntry();
        } else {
            create(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (showing != null) startPolling();
        offers.promptIfNeeded();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopPolling();
        binding = null;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    private SharedTimetable myTimetable() {
        return store.myTimetable(DataCache.getInstance(requireContext()).loadEvents(), System.currentTimeMillis());
    }

    private void showNameEntry() {
        stopPolling();
        binding.editName.setText(store.myName());
        show(binding.groupName);
        binding.editName.requestFocus();
    }

    private void create(boolean forceNew) {
        SharedTimetable timetable = myTimetable();
        if (timetable == null) { show(binding.groupUnavailable); return; }
        show(binding.groupCreating);
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            OutgoingShare share = null;
            String error = null;
            try {
                share = store.startSharing(timetable, forceNew);
            } catch (TimetableShareApi.Failure e) {
                error = e.message(appContext);
            }
            OutgoingShare s = share;
            String err = error;
            main.post(() -> {
                if (binding == null) return;
                if (s != null) showShare(s); else fail(err);
            });
        });
    }

    private void showShare(@NonNull OutgoingShare share) {
        showing = share;
        int size = Math.round(240 * getResources().getDisplayMetrics().density);
        Bitmap qr = QrCodes.make(TimetableShareLink.appUrl(share.shareId), size);
        binding.imageQr.setImageBitmap(qr);
        String name = store.myName();
        binding.textSendingAs.setText(name.isEmpty()
                ? getString(R.string.timetable_exchange_sending_unnamed)
                : getString(R.string.timetable_exchange_sending_as_format, name));
        binding.textExpiry.setText(TimetableDates.until(requireContext(), share.expiresAt));
        show(binding.groupShowing);
        if (isResumed()) startPolling();
    }

    private void fail(@Nullable String message) {
        showing = null;
        stopPolling();
        binding.textFailed.setText(message != null ? message : "");
        show(binding.groupFailed);
    }

    private void show(@NonNull View group) {
        for (View g : new View[]{binding.groupUnavailable, binding.groupName, binding.groupCreating,
                binding.groupShowing, binding.groupFailed}) {
            g.setVisibility(g == group ? View.VISIBLE : View.GONE);
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        main.post(poll);
    }

    private void stopPolling() {
        polling = false;
        main.removeCallbacks(poll);
    }

    private void pollOnce() {
        if (!polling || pollInFlight || showing == null) return;
        pollInFlight = true;
        executor.execute(() -> {
            List<TimetableFriend> received = null;
            try {
                received = store.collectReplies();
            } catch (TimetableShareApi.Failure ignored) {
                // 通信できないときは次の回にもう一度見る。
            }
            boolean gone = store.outgoing() == null;
            List<TimetableFriend> r = received;
            main.post(() -> {
                pollInFlight = false;
                if (binding == null || !polling) return;
                if (r != null && !r.isEmpty()) {
                    addReceived(r);
                    offers.promptIfNeeded();
                }
                if (gone) {
                    fail(getString(R.string.timetable_exchange_expired));
                    return;
                }
                main.postDelayed(poll, POLL_MS);
            });
        });
    }

    private void addReceived(@NonNull List<TimetableFriend> friends) {
        binding.rowWaiting.setVisibility(View.GONE);
        binding.textReceivedMore.setVisibility(View.VISIBLE);
        for (TimetableFriend f : friends) {
            // 同じ人の返事は 1 行にまとめる (送り直し・2 台目など)。行には友達の id を付けておき、あれば名前だけ書き直す。
            View existing = binding.listReceived.findViewWithTag(f.id);
            if (existing instanceof TextView) {
                ((TextView) existing).setText(getString(R.string.timetable_exchange_received_format, f.name));
                continue;
            }
            TextView t = new TextView(requireContext());
            t.setTag(f.id);
            t.setText(getString(R.string.timetable_exchange_received_format, f.name));
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0);
            t.setCompoundDrawablePadding(Math.round(8 * getResources().getDisplayMetrics().density));
            t.setCompoundDrawableTintList(ContextCompat.getColorStateList(requireContext(), R.color.success));
            t.setPadding(0, 4, 0, 4);
            binding.listReceived.addView(t);
        }
        binding.getRoot().performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    @NonNull
    private static String text(@NonNull TextView v) {
        return v.getText() != null ? v.getText().toString() : "";
    }
}
