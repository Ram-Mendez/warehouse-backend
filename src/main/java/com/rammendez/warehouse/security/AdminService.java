package com.rammendez.warehouse.security;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.auth.AuthRepository;
import com.rammendez.warehouse.auth.RefreshTokenRepository;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.warehouse.WarehouseRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
public class AdminService {
    private final SecurityRepository repository;
    private final SecurityRoleRepository roles;
    private final WarehouseScopeRepository scopes;
    private final AuthRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final WarehouseRepository warehouses;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public AdminService(
            SecurityRepository repository,
            SecurityRoleRepository roles,
            WarehouseScopeRepository scopes,
            AuthRepository sessions,
            RefreshTokenRepository refreshTokens,
            WarehouseRepository warehouses,
            PasswordEncoder passwords,
            AuditService audit) {
        this.repository = repository;
        this.roles = roles;
        this.scopes = scopes;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.warehouses = warehouses;
        this.passwords = passwords;
        this.audit = audit;
    }

    public PageResponse<AdminDtos.UserSummary> listUserSummaries(int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = repository.findAll(PageRequest.of(page, size, Sort.by("id")));
        return new PageResponse<>(result.getContent().stream().map(SecurityUser::toSummary).toList(),
                result.getTotalElements(), page, size);
    }

    public AdminDtos.UserResponse getUserWithRolesAndWarehouseScopes(long id) {
        var user = repository.findUserById(id).orElseThrow(() -> BusinessException.missing("User"));
        return new AdminDtos.UserResponse(id, user.username(), user.email(), user.enabled(), user.locked(),
                repository.findUserRoleCodes(id), scopes.findByUserIdOrderByWarehouseId(id).stream()
                        .map(WarehouseScope::toResponse).toList());
    }

    public List<AdminDtos.Role> listAvailableRoles() {
        return roles.findAllByOrderByCode().stream().map(SecurityRole::toResponse).toList();
    }

    @Transactional
    public AdminDtos.UserResponse create(AdminDtos.Create input) {
        PasswordPolicy.requirePasswordWithinBcryptByteLimit(input.password());
        long id = repository.saveAndFlush(new SecurityUser(
                input.username().trim(), input.email(), passwords.encode(input.password()))).getId();
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_CREATED", "security_user", id);
        return getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse updateUserAndRevokeAuthSessions(long id, AdminDtos.Update input) {
        var user = lockUser(id);
        getUserWithRolesAndWarehouseScopes(id);
        if (input.password() != null) {
            PasswordPolicy.requirePasswordWithinBcryptByteLimit(input.password());
        }
        user.update(input, input.password() == null ? null : passwords.encode(input.password()));
        repository.flush();
        revokeAllUserAuthSessionsAndRefreshTokens(id);
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_UPDATED", "security_user", id);
        return getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse replaceUserRolesAndRevokeAuthSessions(long id, AdminDtos.Roles input) {
        var user = lockUser(id);
        getUserWithRolesAndWarehouseScopes(id);
        var available = listAvailableRoles();
        var roleIds =
                input.roles().stream()
                        .map(
                                code ->
                                        available.stream()
                                                .filter(r -> r.code().equals(code))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                BusinessException.missing(
                                                                        "Role " + code))
                                                .id())
                        .toList();
        user.replaceRoles(new LinkedHashSet<>(roles.findAllById(roleIds)));
        repository.flush();
        revokeAllUserAuthSessionsAndRefreshTokens(id);
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_ROLES_CHANGED", "security_user", id);
        return getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse replaceUserWarehouseScopes(long id, AdminDtos.Scopes input) {
        lockUser(id);
        getUserWithRolesAndWarehouseScopes(id);
        if (input.scopes().stream().map(AdminDtos.Scope::warehouseId).distinct().count()
                != input.scopes().size()) {
            throw BusinessException.invalid("Duplicate warehouse scope");
        }
        input.scopes().forEach(scope -> warehouses.getWarehouse(scope.warehouseId()));
        scopes.deleteByUserId(id);
        // Flush removals before inserting replacement composite keys.
        scopes.flush();
        scopes.saveAllAndFlush(input.scopes().stream()
                .map(scope -> new WarehouseScope(id, scope.warehouseId(), scope.scopeRole())).toList());
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_SCOPES_CHANGED", "security_user", id);
        return getUserWithRolesAndWarehouseScopes(id);
    }

    private SecurityUser lockUser(long id) {
        return repository.findAndLockById(id).orElseThrow(() -> BusinessException.missing("User"));
    }

    private void revokeAllUserAuthSessionsAndRefreshTokens(long id) {
        sessions.revokeAllUserSessions(id);
        refreshTokens.revokeAllUserTokens(id);
    }
}
