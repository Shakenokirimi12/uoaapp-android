package com.shakenokirimi12.uoa_app.data.models;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class FacilityUsage {

    public enum Status { AVAILABLE, BUSY, OUTSIDE_HOURS }

    private String name;
    private Status currentStatus;
    private String statusMessage;
    private List<ScheduleItem> schedule = new ArrayList<>();

    public FacilityUsage(String name) {
        this.name = name;
        this.currentStatus = Status.AVAILABLE;
        this.statusMessage = "利用可能";
    }

    public String getName() { return name; }

    public Status getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(Status s) { this.currentStatus = s; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }

    public List<ScheduleItem> getSchedule() { return schedule; }
    public void addScheduleItem(ScheduleItem item) { schedule.add(item); }

    public void computeCurrentStatus() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
        int nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        if (nowMinutes < 360) {
            currentStatus = Status.OUTSIDE_HOURS;
            statusMessage = "時間外 (06:00から利用可能)";
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
            statusMessage = "利用中 (〜" + currentEvent.getEndTime() + "まで)";
        } else if (nextEvent != null) {
            currentStatus = Status.AVAILABLE;
            statusMessage = "利用可能 (" + nextEvent.getStartTime() + "から利用予定)";
        } else {
            currentStatus = Status.AVAILABLE;
            statusMessage = "利用可能";
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
