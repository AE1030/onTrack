package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The shared, trusted syllabus catalog. Not owned by anyone, and deliberately outside the tenant
 * contract — it must stay readable by every student, so it implements neither {@code UserOwned} nor
 * the owned document base class.
 */
@Document(collection = "syllabuses")
public class SyllabusDocument extends AbstractSyllabusDocument {

    @Id
    private CourseTermId id;

    @Field("doc_code")
    private String docCode;

    public CourseTermId getId() {
        return id;
    }

    public void setId(CourseTermId id) {
        this.id = id;
    }

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }
}

