package com.shakenokirimi12.uoa_app.services.otprelay;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** 承認通知の「承認して送る」「拒否」。処理は OtpRelayWorker に回す (通信があるため)。 */
public class OtpRelayActionReceiver extends BroadcastReceiver {
    static final String ACTION_APPROVE = "com.shakenokirimi12.uoa_app.OTP_RELAY_APPROVE";
    static final String ACTION_REJECT = "com.shakenokirimi12.uoa_app.OTP_RELAY_REJECT";

    @NonNull
    static PendingIntent pendingIntent(@NonNull Context ctx, @NonNull String action, @NonNull OtpRelayRequest request,
                                       int notificationId) {
        Intent intent = new Intent(ctx, OtpRelayActionReceiver.class).setAction(action);
        OtpRelayWorker.putRequest(intent, request);
        // 承認と拒否で別の PendingIntent にする (requestCode が同じだと extras が上書きされる)。
        int code = notificationId ^ (ACTION_APPROVE.equals(action) ? 1 : 2);
        return PendingIntent.getBroadcast(ctx, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        OtpRelayRequest request = OtpRelayWorker.getRequest(intent);
        if (request == null) return;
        if (ACTION_APPROVE.equals(intent.getAction())) {
            OtpRelayWorker.enqueue(context, OtpRelayWorker.MODE_APPROVE, request, request.requestedAt);
        } else if (ACTION_REJECT.equals(intent.getAction())) {
            OtpRelayNotifications.cancel(context, request);
            OtpRelayWorker.enqueue(context, OtpRelayWorker.MODE_REJECT, request, request.requestedAt);
        }
    }
}
