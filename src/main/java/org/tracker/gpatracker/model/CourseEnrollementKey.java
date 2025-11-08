package org.tracker.gpatracker.model;

/*For many to many relationships, sometimes it is not possible to have unique enteries so we need
 a composite key as a column for our jointable. For an example if we have courses and students and then
 we will have repeating course and student enteries but the key is that each row is unique so we
 will use that to make our id for new jointable*/

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.springframework.context.annotation.Configuration;
//Note: Implementing Serializable (an interface) is necessary for composite keys in JPA to ensure that the key can be serialized and deserialized correctly, which is essential for the persistence context to manage entity states effectively.
//In short: Serialization = Object → Bytes
/*Common use cases

Saving application state.

Caching objects.

Sending objects over the network (e.g., RMI, sockets, messaging).

Storing objects in files or databases in a portable format.*/
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class CourseEnrollementKey implements Serializable {

    @Column(name = "student_id")
    Long studentId;

    @Column(name = "course_id")
    Long courseId;

    public CourseEnrollementKey() {
    }

    public CourseEnrollementKey(Long studentId, Long courseId) {
        this.studentId = studentId;
        this.courseId = courseId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }
    /*equals() and hashCode() are needed because:

JPA/Hibernate often stores entities (and their IDs) in collections like HashSet or HashMap inside its persistence context (the first-level cache).

If your ID objects don’t implement proper equals()/hashCode(), Hibernate can’t tell when two composite keys actually represent the same row.

Example: new StudentCourseId(1, 101) vs another new StudentCourseId(1, 101) would look different to Java without equals()/hashCode().

This could cause duplicate entries in memory, failed lookups, or weird bugs when persisting/fetching.

✅ In other words

Database: enforces uniqueness of rows via the PK constraint.

Java side: equals() and hashCode() let Hibernate (and Java collections) correctly recognize that two key objects represent the same database row.*/

    //Telling java to override the generic equals and hash mthods found in the object class and use this implementation I gave you instead
    //The way JPA

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CourseEnrollementKey that = (CourseEnrollementKey) o;
        return Objects.equals(studentId, that.studentId) && Objects.equals(courseId, that.courseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(studentId, courseId);
    }
}
