package com.shakenokirimi12.uoa_app.ui.notifications;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.PushNotification;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class NotificationListFragment extends Fragment {

    private PushNotificationService pushService;
    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView textEmpty;
    private final List<PushNotification> notifications = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notification_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pushService = new PushNotificationService();
        pushService.init(PreferenceManager.getInstance(requireContext()).getDeviceId());

        recycler = view.findViewById(R.id.recycler_notifications);
        progress = view.findViewById(R.id.progress);
        textEmpty = view.findViewById(R.id.text_empty);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        view.findViewById(R.id.btn_mark_all_read).setOnClickListener(v -> markAllRead());

        fetchNotifications();
    }

    private void fetchNotifications() {
        progress.setVisibility(View.VISIBLE);
        pushService.fetchNotifications(new PushNotificationService.NotificationCallback() {
            @Override
            public void onResult(List<PushNotification> result, int unreadCount) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                notifications.clear();
                notifications.addAll(result);
                updateUI();
            }
            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void updateUI() {
        if (notifications.isEmpty()) {
            textEmpty.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        } else {
            textEmpty.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
            recycler.setAdapter(new NotificationAdapter());
        }
    }

    private void markAllRead() {
        for (PushNotification n : notifications) {
            if (!n.isRead()) {
                pushService.markAsRead(n.getId());
            }
        }
        fetchNotifications();
    }

    class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            PushNotification n = notifications.get(pos);
            h.title.setText(n.getTitle());
            h.body.setText(n.getBody());
            h.unreadDot.setVisibility(n.isRead() ? View.GONE : View.VISIBLE);

            Date created = n.getCreatedDate();
            if (created != null) {
                h.time.setText(DateUtils.getRelativeTimeSpanString(
                        created.getTime(), System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS));
            }

            h.itemView.setOnClickListener(v -> {
                if (n.getUrl() != null && !n.getUrl().isEmpty()) {
                    try {
                        new CustomTabsIntent.Builder().build()
                                .launchUrl(requireContext(), android.net.Uri.parse(n.getUrl()));
                    } catch (Exception ignored) {}
                }
                if (!n.isRead()) {
                    pushService.markAsRead(n.getId());
                }
            });
        }

        @Override public int getItemCount() { return notifications.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView title, body, time;
            View unreadDot;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.text_title);
                body = v.findViewById(R.id.text_body);
                time = v.findViewById(R.id.text_time);
                unreadDot = v.findViewById(R.id.dot_unread);
            }
        }
    }
}
