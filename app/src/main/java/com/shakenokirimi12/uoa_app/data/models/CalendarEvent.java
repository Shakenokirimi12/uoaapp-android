package com.shakenokirimi12.uoa_app.data.models;

import java.util.Date;

public class CalendarEvent {
    private String summary;
    private String location;
    private Date dtstart;
    private Date dtend;
    private String uid;

    public CalendarEvent(String summary, String location, Date dtstart, Date dtend) {
        this.summary = summary;
        this.location = location;
        this.dtstart = dtstart;
        this.dtend = dtend;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getLocation() { return location != null ? location : ""; }
    public void setLocation(String location) { this.location = location; }

    public Date getDtstart() { return dtstart; }
    public void setDtstart(Date dtstart) { this.dtstart = dtstart; }

    public Date getDtend() { return dtend; }
    public void setDtend(Date dtend) { this.dtend = dtend; }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public long getDurationMinutes() {
        if (dtstart == null || dtend == null) return 0;
        return (dtend.getTime() - dtstart.getTime()) / (60 * 1000);
    }

    public boolean isActiveAt(Date date) {
        if (dtstart == null || dtend == null) return false;
        long t = date.getTime();
        return t >= dtstart.getTime() && t <= dtend.getTime();
    }
}
