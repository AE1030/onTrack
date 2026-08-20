package org.tracker.gpatracker.courses.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Convert;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.accounts.service.StringGradeEncryptionConverter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

@Entity
// Must be declared here, on the concrete entity: Hibernate does not inherit @Filter from a
// @MappedSuperclass, and omitting it leaves the table silently unfiltered.
@Filter(name = OwnerFilter.NAME)
public class PastCourse extends UserOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owner lives in UserOwnedEntity.ownerId, mapped to the same student_id column.
    // The @ManyToOne that used to be here would be a duplicate mapping of that column.
    private String name;
    @Convert(converter = StringGradeEncryptionConverter.class)
    private String grade;
    private String units;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }
}
