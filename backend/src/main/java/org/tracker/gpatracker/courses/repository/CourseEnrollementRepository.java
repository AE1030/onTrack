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

    List<CourseEnrollement> findByStudentsIdAndIncludeInGpaTrue(Long studentId);

    long countByStudentsId(Long studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CourseEnrollement e WHERE e.id = :id")
    Optional<CourseEnrollement> findByIdForUpdate(@Param("id") CourseEnrollementKey id);
}
