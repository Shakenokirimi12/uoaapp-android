package com.shakenokirimi12.uoa_app.data.models;

public class Assignment {
    private int id;
    private String name;
    private String courseName;
    private int courseId;
    private long dueDate;       // epoch seconds
    private long allowSubmissionsFrom;
    private String intro;
    private boolean submitted;

    public Assignment() {}

    public Assignment(int id, String name, String courseName, int courseId, long dueDate) {
        this.id = id;
        this.name = name;
        this.courseName = courseName;
        this.courseId = courseId;
        this.dueDate = dueDate;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public int getCourseId() { return courseId; }
    public void setCourseId(int courseId) { this.courseId = courseId; }

    public long getDueDate() { return dueDate; }
    public void setDueDate(long dueDate) { this.dueDate = dueDate; }

    public long getAllowSubmissionsFrom() { return allowSubmissionsFrom; }
    public void setAllowSubmissionsFrom(long from) { this.allowSubmissionsFrom = from; }

    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }

    public boolean isSubmitted() { return submitted; }
    public void setSubmitted(boolean submitted) { this.submitted = submitted; }

    public boolean isDuePast() {
        return dueDate > 0 && dueDate < System.currentTimeMillis() / 1000;
    }
}
