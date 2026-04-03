package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Objects;

public class CourseTermId {
    @Field("course_code")
    private String courseCode;
    private String term;

    public CourseTermId() {
    }

    public CourseTermId(String courseCode, String term) {
        this.courseCode = courseCode;
        this.term = term;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CourseTermId that = (CourseTermId) o;
        return Objects.equals(courseCode, that.courseCode) && Objects.equals(term, that.term);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseCode, term);
    }
}
