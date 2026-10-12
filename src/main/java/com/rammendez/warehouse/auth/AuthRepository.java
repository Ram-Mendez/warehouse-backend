package com.rammendez.warehouse.auth;

import com.rammendez.warehouse.common.BusinessException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface AuthRepository extends JpaRepository<AuthSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :sessionId")
    Optional<AuthSession> findAndLockById(UUID sessionId);

    default AuthSession lockAndGetAuthSession(UUID sessionId) {
        return findAndLockById(sessionId).orElseThrow(() -> BusinessException.missing("Session"));
    }

    @Modifying(flushAutomatically = true)
    @Query("update AuthSession s set s.lastSeenAt = current_timestamp where s.id = :id")
    void updateAuthSessionLastSeenAt(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = coalesce(s.revokedAt, current_timestamp)
            where s.id = :id
            """)
    void revokeSession(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = coalesce(s.revokedAt, current_timestamp)
            where s.user.id = :userId
            """)
    void revokeAllUserSessions(long userId);
}
