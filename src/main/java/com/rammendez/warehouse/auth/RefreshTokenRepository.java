package com.rammendez.warehouse.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<AuthRefreshToken, UUID> {
    record RefreshToken(UUID id, UUID sessionId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    // A projection always re-reads the database after the family lock; an earlier token
    // lookup must not leave a stale managed entity in the persistence context.
    @Query("""
            select new com.rammendez.warehouse.auth.RefreshTokenRepository$RefreshToken(
                t.id, t.session.id, t.expiresAt, t.usedAt, t.revokedAt)
            from AuthRefreshToken t where t.tokenHash = :hash
            """)
    Optional<RefreshToken> findRefreshTokenByHash(String hash);

    @Modifying(flushAutomatically = true)
    @Query("update AuthRefreshToken t set t.usedAt = current_timestamp where t.id = :id")
    void markRefreshTokenUsed(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthRefreshToken t set t.revokedAt = coalesce(t.revokedAt, current_timestamp)
            where t.session.id = :sessionId
            """)
    void revokeSessionTokens(UUID sessionId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthRefreshToken t set t.revokedAt = coalesce(t.revokedAt, current_timestamp)
            where t.session.id in (select s.id from AuthSession s where s.user.id = :userId)
            """)
    void revokeAllUserTokens(long userId);
}
