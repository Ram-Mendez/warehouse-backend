package com.rammendez.warehouse.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshToken {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private AuthSession session;
    @Column(name = "parent_token_id")
    private UUID parentTokenId;
    @Column(name = "token_hash")
    private String tokenHash;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthRefreshToken() {}
    public AuthRefreshToken(AuthSession session, UUID parentTokenId, String hash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.parentTokenId = parentTokenId;
        this.tokenHash = hash;
        this.expiresAt = expiresAt;
    }
}
