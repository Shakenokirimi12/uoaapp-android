package com.shakenokirimi12.uoa_app.data.models;

public class Review {
    private String id;
    private String courseId;
    private String courseName;
    private int enrollmentYear;
    private int rating;
    private String title;
    private String body;
    /**
     * 閲覧者自身の投稿か (サーバーが Bearer トークンから判定する)。投稿者 ID は公開されなくなったため、
     * 編集・削除と報告・ブロックの出し分けはこれだけで行う。トークン無しで取得したときは常に false。
     */
    private boolean mine;
    private int reportCount;
    private String createdAt;
    private String reviewType;
    private String instructor;
    // 編集時にそのまま送り返す。Android の投稿画面には入力欄が無いが、送らないとサーバーが空で上書きし、
    // iOS で入力した得点・成績が消える。
    private String studentScore;
    private String studentGrade;

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

    public boolean isMine() { return mine; }
    public void setMine(boolean mine) { this.mine = mine; }

    public int getReportCount() { return reportCount; }
    public void setReportCount(int reportCount) { this.reportCount = reportCount; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getReviewType() { return reviewType != null ? reviewType : "course"; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }

    public String getInstructor() { return instructor; }
    public void setInstructor(String instructor) { this.instructor = instructor; }

    public String getStudentScore() { return studentScore; }
    public void setStudentScore(String studentScore) { this.studentScore = studentScore; }

    public String getStudentGrade() { return studentGrade; }
    public void setStudentGrade(String studentGrade) { this.studentGrade = studentGrade; }

    public boolean isInstructorReview() { return "instructor".equals(reviewType); }

    public String getRatingStars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < rating ? "★" : "☆");
        }
        return sb.toString();
    }
}
