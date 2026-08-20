package org.tracker.gpatracker.assessmenttable.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.tracker.gpatracker.tenancy.mongo.UserOwnedDocument;

import java.util.List;

@Document(collection = "assessmentTable")
// Without an index every findByStudentId... is a full collection scan.
@CompoundIndex(name = "idx_assessment_student_course_term", def = "{'studentId': 1, 'courseCode': 1, 'term': 1}")
public class AssessmentTableDocument extends UserOwnedDocument {
    @Id
    private String id;
    private String courseCode;
    private String term;
    private List<AssessmentScheme> schemes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public List<AssessmentScheme> getSchemes() {
        return schemes;
    }

    public void setSchemes(List<AssessmentScheme> schemes) {
        this.schemes = schemes;
    }
}
