package com.shakenokirimi12.uoa_app.data.models;

public class MoodleCourse {
    private int id;
    private String shortname;
    private String fullname;
    private String displayname;
    private long startdate;
    private long enddate;
    private boolean visible;
    private boolean favourite;

    public MoodleCourse() {}

    public MoodleCourse(int id, String shortname, String fullname) {
        this.id = id;
        this.shortname = shortname;
        this.fullname = fullname;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getShortname() { return shortname; }
    public void setShortname(String shortname) { this.shortname = shortname; }

    public String getFullname() { return fullname; }
    public void setFullname(String fullname) { this.fullname = fullname; }

    public String getDisplayname() { return displayname != null ? displayname : fullname; }
    public void setDisplayname(String displayname) { this.displayname = displayname; }

    public long getStartdate() { return startdate; }
    public void setStartdate(long startdate) { this.startdate = startdate; }

    public long getEnddate() { return enddate; }
    public void setEnddate(long enddate) { this.enddate = enddate; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isFavourite() { return favourite; }
    public void setFavourite(boolean favourite) { this.favourite = favourite; }
}
