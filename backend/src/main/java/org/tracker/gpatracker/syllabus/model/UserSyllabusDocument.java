package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "userSyllabiExtraction")
public class UserSyllabusDocument extends AbstractSyllabusDocument {
    @Field("student_id")
    private Long studentId;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }
}
