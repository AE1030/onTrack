package org.tracker.gpatracker.courses.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CourseEnrollementRepository extends UserScopedRepository<CourseEnrollement, CourseEnrollementKey> {
    List<CourseEnrollement> findByStudentsId(Long studentId);

    /**
     * Every term's enrollments. Callers that feed a GPA or a calendar almost always want
     * {@link #findByStudentsIdAndIdTermAndIncludeInGpaTrue} instead: once past terms exist, this
     * returns rows the student has already graduated past.
     */
    List<CourseEnrollement> findByStudentsIdAndIncludeInGpaTrue(Long studentId);

    // "IdTerm" is how Spring Data walks into the embedded id, i.e. e.id.term.
    List<CourseEnrollement> findByStudentsIdAndIdTerm(Long studentId, String term);

    List<CourseEnrollement> findByStudentsIdAndIdTermAndIncludeInGpaTrue(Long studentId, String term);

    /** Per term, not per student. A student's second term must not inherit their first term's cap. */
    long countByStudentsIdAndIdTerm(Long studentId, String term);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CourseEnrollement e WHERE e.id = :id")
    Optional<CourseEnrollement> findByIdForUpdate(@Param("id") CourseEnrollementKey id);
}
