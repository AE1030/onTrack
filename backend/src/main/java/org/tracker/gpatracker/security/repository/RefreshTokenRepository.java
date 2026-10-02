package org.tracker.gpatracker.security.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.security.model.RefreshToken;
import org.tracker.gpatracker.security.model.Users;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Row-locked so two concurrent refreshes with the same token serialise: the second one sees the
     * first one's revocation instead of both rotating it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.user = :user and t.revokedAt is null")
    int revokeAllForUser(@Param("user") Users user, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.user = :user and t.expiresAt < :now")
    int deleteExpiredForUser(@Param("user") Users user, @Param("now") Instant now);
}
