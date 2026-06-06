package com.shakenokirimi12.uoa_app.data.models;

public class ReviewCourse {
    private String courseId;
    private String courseName;
    private String instructor;
    private int credits;
    private String category;
    private String syllabusUrl;
    private int reviewCount;
    private double avgRating;

    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getInstructor() { return instructor; }
    public void setInstructor(String instructor) { this.instructor = instructor; }

    public int getCredits() { return credits; }
    public void setCredits(int credits) { this.credits = credits; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSyllabusUrl() { return syllabusUrl; }
    public void setSyllabusUrl(String syllabusUrl) { this.syllabusUrl = syllabusUrl; }

    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }

    public double getAvgRating() { return avgRating; }
    public void setAvgRating(double avgRating) { this.avgRating = avgRating; }

    public String getAvgRatingStars() {
        int rounded = (int) Math.round(avgRating);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < rounded ? "★" : "☆");
        }
        return sb.toString();
    }
}
