package com.rammendez.warehouse.auth;

import com.rammendez.warehouse.security.SecurityUser;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_session")
public class AuthSession {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private SecurityUser user;
    @Column(name = "last_seen_at", insertable = false)
    private Instant lastSeenAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthSession() {}
    public AuthSession(SecurityUser user) {
        this.id = UUID.randomUUID();
        this.user = user;
    }
    public UUID id() { return id; }
    public long userId() { return user.getId(); }
    public Instant revokedAt() { return revokedAt; }
}
