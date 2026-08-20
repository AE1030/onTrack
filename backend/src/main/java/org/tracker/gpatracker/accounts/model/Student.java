package org.tracker.gpatracker.accounts.model;

import org.tracker.gpatracker.tenancy.BaseEntity;
import jakarta.persistence.*;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;
import org.tracker.gpatracker.security.model.Users;

import java.math.BigDecimal;

@Entity
public class Student extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;

    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    BigDecimal gpa4;

    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    BigDecimal gpa12;

    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    BigDecimal targetGpa4;

    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    BigDecimal targetGpa12;

    //Relationship between users and student, the student is the owning side of the relationship
    @OneToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")//because I didn't name the column user_id in the Users class I have to specify it here
    private Users user;

    // The inverse collections that used to live here (enroll, dueDateOverrides) were removed:
    // nothing read them, and a filtered collection load looks like mass orphaning to Hibernate.
    // With orphanRemoval=true on dueDateOverrides that would have issued DELETEs on flush.
    // Both are queried directly through their repositories instead.

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public BigDecimal getGpa4() {
        return gpa4;
    }

    public void setGpa4(BigDecimal gpa4) {
        this.gpa4 = gpa4;
    }

    public BigDecimal getGpa12() {
        return gpa12;
    }

    public void setGpa12(BigDecimal gpa12) {
        this.gpa12 = gpa12;
    }

    public BigDecimal getTargetGpa4() {
        return targetGpa4;
    }

    public void setTargetGpa4(BigDecimal targetGpa4) {
        this.targetGpa4 = targetGpa4;
    }

    public BigDecimal getTargetGpa12() {
        return targetGpa12;
    }

    public void setTargetGpa12(BigDecimal targetGpa12) {
        this.targetGpa12 = targetGpa12;
    }

    public Users getUser() {
        return user;
    }

    public void setUser(Users user) {
        this.user = user;
    }




}
