package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "syllabuses")
public class SyllabusDocument extends AbstractSyllabusDocument {
    @Field("doc_code")
    private String docCode;

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }
}

