package com.shakenokirimi12.uoa_app.data.models;

public class Review {
    private String id;
    private String courseId;
    private String courseName;
    private int enrollmentYear;
    private int rating;
    private String title;
    private String body;
    private String internalUserId;
    private int reportCount;
    private String createdAt;
    private String reviewType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public int getEnrollmentYear() { return enrollmentYear; }
    public void setEnrollmentYear(int enrollmentYear) { this.enrollmentYear = enrollmentYear; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getInternalUserId() { return internalUserId; }
    public void setInternalUserId(String internalUserId) { this.internalUserId = internalUserId; }

    public int getReportCount() { return reportCount; }
    public void setReportCount(int reportCount) { this.reportCount = reportCount; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getReviewType() { return reviewType != null ? reviewType : "course"; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }

    public boolean isInstructorReview() { return "instructor".equals(reviewType); }

    public String getRatingStars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < rating ? "★" : "☆");
        }
        return sb.toString();
    }
}
