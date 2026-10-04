package com.rammendez.warehouse.auth;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.security.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private final SecurityRepository users;
    private final AuthRepository repository;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AccessControl access;
    private final TransactionTemplate transaction;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(
            SecurityRepository users,
            AuthRepository repository,
            PasswordEncoder passwords,
            JwtEncoder encoder,
            AccessControl access,
            PlatformTransactionManager manager,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.access-token-ttl}") Duration accessTtl,
            @Value("${security.jwt.refresh-token-ttl}") Duration refreshTtl) {
        this.users = users;
        this.repository = repository;
        this.passwords = passwords;
        this.encoder = encoder;
        this.access = access;
        this.transaction = new TransactionTemplate(manager);
        this.issuer = issuer;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public AuthDtos.Tokens login(AuthDtos.Login input) {
        if (!PasswordPolicy.supported(input.password())) {
            throw unauthorized();
        }
        return transaction.execute(
                status -> {
                    var account = users.lockByUsername(input.username());
                    boolean matches =
                            passwords.matches(
                                    input.password(),
                                    account.map(UserAccount::passwordHash).orElse(dummyHash));
                    if (!matches
                            || account.isEmpty()
                            || !account.get().enabled()
                            || account.get().locked()) {
                        throw unauthorized();
                    }
                    var user = account.get();
                    return issue(user, repository.session(user.id()), null);
                });
    }

    public AuthDtos.Tokens refresh(String raw) {
        String hash = hash(raw);
        var found = repository.token(hash).orElseThrow(AuthService::unauthorized);
        // Failure is returned from the transaction so reuse revocation commits before the 401.
        var tokens =
                transaction.execute(
                        status -> {
                            var session = repository.lock(found.sessionId());
                            var token =
                                    repository.token(hash).orElseThrow(AuthService::unauthorized);
                            if (token.usedAt() != null) {
                                repository.revoke(session.id());
                                return null;
                            }
                            var user =
                                    users.byId(session.userId())
                                            .orElseThrow(AuthService::unauthorized);
                            if (session.revokedAt() != null
                                    || token.revokedAt() != null
                                    || !token.expiresAt().isAfter(Instant.now())
                                    || !user.enabled()
                                    || user.locked()) {
                                return null;
                            }
                            repository.used(token.id());
                            repository.seen(session.id());
                            return issue(user, session.id(), token.id());
                        });
        if (tokens == null) {
            throw unauthorized();
        }
        return tokens;
    }

    public void logout() {
        UUID sessionId = UUID.fromString(access.jwt().getToken().getClaimAsString("sid"));
        transaction.executeWithoutResult(
                status -> {
                    var session = repository.lock(sessionId);
                    if (session.userId() != access.userId()) {
                        throw unauthorized();
                    }
                    repository.revoke(sessionId);
                });
    }

    public AuthDtos.Me me() {
        var user = users.byId(access.userId()).orElseThrow(AuthService::unauthorized);
        return new AuthDtos.Me(
                user.id(),
                user.username(),
                user.email(),
                users.roles(user.id()),
                users.permissions(user.id()));
    }

    private AuthDtos.Tokens issue(UserAccount user, UUID sessionId, UUID parentId) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTtl);
        repository.insertToken(sessionId, parentId, hash(refresh), now.plus(refreshTtl));
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject(Long.toString(user.id()))
                        .issuedAt(now)
                        .expiresAt(expiry)
                        .claim("sid", sessionId.toString())
                        .claim("av", user.authVersion())
                        .claim("username", user.username())
                        .claim("roles", users.roles(user.id()))
                        .claim("permissions", users.permissions(user.id()))
                        .build();
        String jwt =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        return new AuthDtos.Tokens(jwt, "Bearer", expiry, refresh);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(
                HttpStatus.UNAUTHORIZED, "Invalid credentials or refresh token");
    }
}
