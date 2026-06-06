package com.shakenokirimi12.uoa_app.data.models;

public class Grade {
    private String subjectCode;
    private String courseName;
    private String grade;
    private String credits;
    private String score;
    private String year;
    private String semester;
    private String instructor;

    public Grade() {}

    public Grade(String subjectCode, String courseName, String grade, String credits, String score, String instructor) {
        this.subjectCode = subjectCode;
        this.courseName = courseName;
        this.grade = grade;
        this.credits = credits;
        this.score = score;
        this.instructor = instructor;
    }

    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }

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

    public String getInstructor() { return instructor; }
    public void setInstructor(String instructor) { this.instructor = instructor; }
}
