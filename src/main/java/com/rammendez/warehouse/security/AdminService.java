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

    public PageResponse<AdminDtos.UserSummary> list(int page, int size) {
        return repository.list(page, size);
    }

    public AdminDtos.UserResponse get(long id) {
        return repository.get(id);
    }

    public List<AdminDtos.Role> roles() {
        return repository.roles();
    }

    @Transactional
    public AdminDtos.UserResponse create(AdminDtos.Create input) {
        PasswordPolicy.validate(input.password());
        long id = repository.insert(input, passwords.encode(input.password()));
        audit.event("USER_CREATED", "security_user", id);
        return repository.get(id);
    }

    @Transactional
    public AdminDtos.UserResponse update(long id, AdminDtos.Update input) {
        repository.lockUser(id);
        repository.get(id);
        if (input.password() != null) {
            PasswordPolicy.validate(input.password());
        }
        repository.update(
                id, input, input.password() == null ? null : passwords.encode(input.password()));
        audit.event("USER_UPDATED", "security_user", id);
        return repository.get(id);
    }

    @Transactional
    public AdminDtos.UserResponse roles(long id, AdminDtos.Roles input) {
        repository.lockUser(id);
        repository.get(id);
        var available = repository.roles();
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
        repository.roles(id, roleIds);
        audit.event("USER_ROLES_CHANGED", "security_user", id);
        return repository.get(id);
    }

    @Transactional
    public AdminDtos.UserResponse scopes(long id, AdminDtos.Scopes input) {
        repository.lockUser(id);
        repository.get(id);
        if (input.scopes().stream().map(AdminDtos.Scope::warehouseId).distinct().count()
                != input.scopes().size()) {
            throw BusinessException.invalid("Duplicate warehouse scope");
        }
        input.scopes().forEach(scope -> warehouses.get(scope.warehouseId()));
        repository.scopes(id, input.scopes());
        audit.event("USER_SCOPES_CHANGED", "security_user", id);
        return repository.get(id);
    }
}
