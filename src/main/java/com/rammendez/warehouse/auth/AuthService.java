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
        if (!PasswordPolicy.isPasswordNonNullAndWithinBcryptByteLimit(input.password())) {
            throw createInvalidCredentialsOrRefreshTokenException();
        }
        return transaction.execute(
                status -> {
                    var account = users.findAndLockUserByUsername(input.username());
                    boolean matches =
                            passwords.matches(
                                    input.password(),
                                    account.map(UserAccount::passwordHash).orElse(dummyHash));
                    if (!matches
                            || account.isEmpty()
                            || !account.get().enabled()
                            || account.get().locked()) {
                        throw createInvalidCredentialsOrRefreshTokenException();
                    }
                    var user = account.get();
                    return issueAccessJwtAndPersistRefreshTokenHash(user, repository.createAuthSession(user.id()), null);
                });
    }

    public AuthDtos.Tokens rotateRefreshTokenOrRevokeSessionOnReuse(String raw) {
        String hash = computeRefreshTokenSha256Hash(raw);
        var found = repository.findRefreshTokenByHash(hash).orElseThrow(AuthService::createInvalidCredentialsOrRefreshTokenException);
        // Failure is returned from the transaction so reuse revocation commits before the 401.
        var tokens =
                transaction.execute(
                        status -> {
                            var session = repository.lockAndGetAuthSession(found.sessionId());
                            var token =
                                    repository.findRefreshTokenByHash(hash).orElseThrow(AuthService::createInvalidCredentialsOrRefreshTokenException);
                            if (token.usedAt() != null) {
                                repository.revokeAuthSessionAndAllRefreshTokens(session.id());
                                return null;
                            }
                            var user =
                                    users.findUserById(session.userId())
                                            .orElseThrow(AuthService::createInvalidCredentialsOrRefreshTokenException);
                            if (session.revokedAt() != null
                                    || token.revokedAt() != null
                                    || !token.expiresAt().isAfter(Instant.now())
                                    || !user.enabled()
                                    || user.locked()) {
                                return null;
                            }
                            repository.markRefreshTokenUsed(token.id());
                            repository.updateAuthSessionLastSeenAt(session.id());
                            return issueAccessJwtAndPersistRefreshTokenHash(user, session.id(), token.id());
                        });
        if (tokens == null) {
            throw createInvalidCredentialsOrRefreshTokenException();
        }
        return tokens;
    }

    public void revokeCurrentAuthSessionAndRefreshTokens() {
        UUID sessionId = UUID.fromString(access.requireJwtAuthentication().getToken().getClaimAsString("sid"));
        transaction.executeWithoutResult(
                status -> {
                    var session = repository.lockAndGetAuthSession(sessionId);
                    if (session.userId() != access.getAuthenticatedUserId()) {
                        throw createInvalidCredentialsOrRefreshTokenException();
                    }
                    repository.revokeAuthSessionAndAllRefreshTokens(sessionId);
                });
    }

    public AuthDtos.Me getCurrentUserProfileRolesAndPermissions() {
        var user = users.findUserById(access.getAuthenticatedUserId()).orElseThrow(AuthService::createInvalidCredentialsOrRefreshTokenException);
        return new AuthDtos.Me(
                user.id(),
                user.username(),
                user.email(),
                users.findUserRoleCodes(user.id()),
                users.findUserPermissionCodes(user.id()));
    }

    private AuthDtos.Tokens issueAccessJwtAndPersistRefreshTokenHash(UserAccount user, UUID sessionId, UUID parentId) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTtl);
        repository.insertRefreshTokenHash(sessionId, parentId, computeRefreshTokenSha256Hash(refresh), now.plus(refreshTtl));
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject(Long.toString(user.id()))
                        .issuedAt(now)
                        .expiresAt(expiry)
                        .claim("sid", sessionId.toString())
                        .claim("av", user.authVersion())
                        .claim("username", user.username())
                        .claim("roles", users.findUserRoleCodes(user.id()))
                        .claim("permissions", users.findUserPermissionCodes(user.id()))
                        .build();
        String jwt =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        return new AuthDtos.Tokens(jwt, "Bearer", expiry, refresh);
    }

    static String computeRefreshTokenSha256Hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException createInvalidCredentialsOrRefreshTokenException() {
        return new BusinessException(
                HttpStatus.UNAUTHORIZED, "Invalid credentials or refresh token");
    }
}
