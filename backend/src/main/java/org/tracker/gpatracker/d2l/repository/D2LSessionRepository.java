package org.tracker.gpatracker.d2l.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.d2l.model.D2LSession;

import java.util.Optional;

public interface D2LSessionRepository extends JpaRepository<D2LSession, Long> {

    Optional<D2LSession> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
