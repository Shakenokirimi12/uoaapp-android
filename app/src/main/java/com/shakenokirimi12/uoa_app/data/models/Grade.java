package com.shakenokirimi12.uoa_app.data.models;

public class Grade {
    private String courseName;
    private String grade;
    private String credits;
    private String score;
    private String year;
    private String semester;

    public Grade() {}

    public Grade(String courseName, String grade, String credits, String score) {
        this.courseName = courseName;
        this.grade = grade;
        this.credits = credits;
        this.score = score;
    }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getCredits() { return credits; }
    public void setCredits(String credits) { this.credits = credits; }

    public String getScore() { return score; }
    public void setScore(String score) { this.score = score; }

    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }

    public String getSemester() { return semester; }
    public void setSemester(String semester) { this.semester = semester; }
}
