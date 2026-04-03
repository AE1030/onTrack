package org.tracker.gpatracker.accounts.model;

import jakarta.persistence.*;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.DueDateOverride;
import org.tracker.gpatracker.security.model.Users;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Entity
public class Student {
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

    //Relationship between students and courses, started off as: many students in enroll in many courses (also students can enroll in the SAME course)
    //CourseEnrollement entity now owns this relationship which started off as a many-to-many relationship
    //from the CourseEnrollement perspective this is a many to one relationhip so we have to inverse it here
    //neither course nor student is the owning side
    @OneToMany(mappedBy = "students")
    Set <CourseEnrollement> enroll;

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    List<DueDateOverride> dueDateOverrides = new ArrayList<>();


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

    public Set<CourseEnrollement> getEnroll() {
        return enroll;
    }

    public void setEnroll(Set<CourseEnrollement> enroll) {
        this.enroll = enroll;
    }

    public List<DueDateOverride> getDueDateOverrides() {
        return dueDateOverrides;
    }

    public void setDueDateOverrides(List<DueDateOverride> dueDateOverrides) {
        this.dueDateOverrides = dueDateOverrides;
    }
}
