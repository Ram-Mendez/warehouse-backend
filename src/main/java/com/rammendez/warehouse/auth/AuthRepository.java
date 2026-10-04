package com.rammendez.warehouse.auth;

import com.rammendez.warehouse.common.Sql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthRepository {
    public record Session(UUID id, long userId, Instant revokedAt) {}

    public record RefreshToken(
            UUID id, UUID sessionId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID session(long userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth_session(id,user_id) values (?,?)", id, userId);
        return id;
    }

    public Session lock(UUID sessionId) {
        return Sql.one(
                jdbc.query(
                        "select * from auth_session where id=? for update",
                        (r, n) ->
                                new Session(
                                        r.getObject("id", UUID.class),
                                        r.getLong("user_id"),
                                        Sql.instant(r, "revoked_at")),
                        sessionId),
                "Session");
    }

    public Optional<RefreshToken> token(String hash) {
        return jdbc
                .query(
                        "select * from auth_refresh_token where token_hash=?",
                        (r, n) ->
                                new RefreshToken(
                                        r.getObject("id", UUID.class),
                                        r.getObject("session_id", UUID.class),
                                        Sql.instant(r, "expires_at"),
                                        Sql.instant(r, "used_at"),
                                        Sql.instant(r, "revoked_at")),
                        hash)
                .stream()
                .findFirst();
    }

    public void insertToken(UUID sessionId, UUID parentId, String hash, Instant expiry) {
        jdbc.update(
                "insert into"
                        + " auth_refresh_token(id,session_id,parent_token_id,token_hash,expires_at)"
                        + " values (?,?,?,?,?)",
                UUID.randomUUID(),
                sessionId,
                parentId,
                hash,
                java.sql.Timestamp.from(expiry));
    }

    public void used(UUID id) {
        jdbc.update("update auth_refresh_token set used_at=now() where id=?", id);
    }

    public void seen(UUID id) {
        jdbc.update("update auth_session set last_seen_at=now() where id=?", id);
    }

    public void revoke(UUID id) {
        jdbc.update("update auth_session set revoked_at=coalesce(revoked_at,now()) where id=?", id);
        jdbc.update(
                "update auth_refresh_token set revoked_at=coalesce(revoked_at,now()) where"
                        + " session_id=?",
                id);
    }
}
