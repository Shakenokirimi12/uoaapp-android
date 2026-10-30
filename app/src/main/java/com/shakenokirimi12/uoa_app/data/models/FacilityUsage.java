package com.shakenokirimi12.uoa_app.data.models;

import android.content.Context;

import androidx.annotation.StringRes;

import com.shakenokirimi12.uoa_app.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class FacilityUsage {

    public enum Status { AVAILABLE, BUSY, OUTSIDE_HOURS }

    private String name;
    private Status currentStatus;
    // 文面は画面に出す時点で Context から引く (モデルは Context を持たない)。
    @StringRes private int statusMessageRes;
    private String statusMessageArg;
    private List<ScheduleItem> schedule = new ArrayList<>();

    public FacilityUsage(String name) {
        this.name = name;
        this.currentStatus = Status.AVAILABLE;
        this.statusMessageRes = R.string.facilities_status_available;
    }

    public String getName() { return name; }

    public Status getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(Status s) { this.currentStatus = s; }

    public String getStatusMessage(Context ctx) {
        return statusMessageArg == null
                ? ctx.getString(statusMessageRes)
                : ctx.getString(statusMessageRes, statusMessageArg);
    }

    public List<ScheduleItem> getSchedule() { return schedule; }
    public void addScheduleItem(ScheduleItem item) { schedule.add(item); }

    public void computeCurrentStatus() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
        int nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        if (nowMinutes < 360) {
            currentStatus = Status.OUTSIDE_HOURS;
            statusMessageRes = R.string.facilities_status_outside_hours_message;
            statusMessageArg = null;
            return;
        }

        ScheduleItem currentEvent = null;
        ScheduleItem nextEvent = null;

        for (ScheduleItem item : schedule) {
            if (!item.isAvailable()) {
                if (nowMinutes >= item.getStartMinutes() && nowMinutes < item.getEndMinutes()) {
                    currentEvent = item;
                    break;
                } else if (nowMinutes < item.getStartMinutes() && nextEvent == null) {
                    nextEvent = item;
                }
            }
        }

        if (currentEvent != null) {
            currentStatus = Status.BUSY;
            statusMessageRes = R.string.facilities_status_busy_until_message;
            statusMessageArg = currentEvent.getEndTime();
        } else if (nextEvent != null) {
            currentStatus = Status.AVAILABLE;
            statusMessageRes = R.string.facilities_status_available_from_message;
            statusMessageArg = nextEvent.getStartTime();
        } else {
            currentStatus = Status.AVAILABLE;
            statusMessageRes = R.string.facilities_status_available;
            statusMessageArg = null;
        }
    }

    public static class ScheduleItem {
        private final String timeRange;
        private final boolean available;
        private final String eventName;
        private final int startMinutes;
        private final int endMinutes;

        public ScheduleItem(int startMins, int endMins, boolean available, String eventName) {
            this.startMinutes = startMins;
            this.endMinutes = endMins;
            this.available = available;
            this.eventName = eventName;
            this.timeRange = formatTime(startMins) + " - " + formatTime(endMins);
        }

        public String getTimeRange() { return timeRange; }
        public boolean isAvailable() { return available; }
        public String getEventName() { return eventName; }
        public int getStartMinutes() { return startMinutes; }
        public int getEndMinutes() { return endMinutes; }

        public String getStartTime() { return formatTime(startMinutes); }
        public String getEndTime() { return formatTime(endMinutes); }

        private static String formatTime(int mins) {
            return String.format(Locale.US, "%02d:%02d", mins / 60, mins % 60);
        }
    }
}
