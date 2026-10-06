package com.rammendez.warehouse.security;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.warehouse.WarehouseRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
public class AdminService {
    private final AdminRepository repository;
    private final WarehouseRepository warehouses;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public AdminService(
            AdminRepository repository,
            WarehouseRepository warehouses,
            PasswordEncoder passwords,
            AuditService audit) {
        this.repository = repository;
        this.warehouses = warehouses;
        this.passwords = passwords;
        this.audit = audit;
    }

    public PageResponse<AdminDtos.UserSummary> listUserSummaries(int page, int size) {
        return repository.listUserSummaries(page, size);
    }

    public AdminDtos.UserResponse getUserWithRolesAndWarehouseScopes(long id) {
        return repository.getUserWithRolesAndWarehouseScopes(id);
    }

    public List<AdminDtos.Role> listAvailableRoles() {
        return repository.listAvailableRoles();
    }

    @Transactional
    public AdminDtos.UserResponse create(AdminDtos.Create input) {
        PasswordPolicy.requirePasswordWithinBcryptByteLimit(input.password());
        long id = repository.insertUserWithPasswordHash(input, passwords.encode(input.password()));
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_CREATED", "security_user", id);
        return repository.getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse updateUserAndRevokeAuthSessions(long id, AdminDtos.Update input) {
        repository.lockUser(id);
        repository.getUserWithRolesAndWarehouseScopes(id);
        if (input.password() != null) {
            PasswordPolicy.requirePasswordWithinBcryptByteLimit(input.password());
        }
        repository.updateUserAdvanceAuthVersionAndRevokeSessions(
                id, input, input.password() == null ? null : passwords.encode(input.password()));
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_UPDATED", "security_user", id);
        return repository.getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse replaceUserRolesAndRevokeAuthSessions(long id, AdminDtos.Roles input) {
        repository.lockUser(id);
        repository.getUserWithRolesAndWarehouseScopes(id);
        var available = repository.listAvailableRoles();
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
        repository.replaceUserRolesAdvanceAuthVersionAndRevokeSessions(id, roleIds);
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_ROLES_CHANGED", "security_user", id);
        return repository.getUserWithRolesAndWarehouseScopes(id);
    }

    @Transactional
    public AdminDtos.UserResponse replaceUserWarehouseScopes(long id, AdminDtos.Scopes input) {
        repository.lockUser(id);
        repository.getUserWithRolesAndWarehouseScopes(id);
        if (input.scopes().stream().map(AdminDtos.Scope::warehouseId).distinct().count()
                != input.scopes().size()) {
            throw BusinessException.invalid("Duplicate warehouse scope");
        }
        input.scopes().forEach(scope -> warehouses.getWarehouse(scope.warehouseId()));
        repository.replaceUserWarehouseScopes(id, input.scopes());
        audit.recordAuditEventWithActorAndWarehouseReferences("USER_SCOPES_CHANGED", "security_user", id);
        return repository.getUserWithRolesAndWarehouseScopes(id);
    }
}
