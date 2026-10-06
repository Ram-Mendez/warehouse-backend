package com.rammendez.warehouse.security;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public PageResponse<AdminDtos.UserSummary> listUserSummaries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listUserSummaries(page, size);
    }

    @GetMapping("/users/{id}")
    public AdminDtos.UserResponse getUserWithRolesAndWarehouseScopes(@PathVariable long id) {
        return service.getUserWithRolesAndWarehouseScopes(id);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.UserResponse create(@Valid @RequestBody AdminDtos.Create input) {
        return service.create(input);
    }

    @PatchMapping("/users/{id}")
    public AdminDtos.UserResponse updateUserAndRevokeAuthSessions(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Update input) {
        return service.updateUserAndRevokeAuthSessions(id, input);
    }

    @PutMapping("/users/{id}/roles")
    public AdminDtos.UserResponse replaceUserRolesAndRevokeAuthSessions(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Roles input) {
        return service.replaceUserRolesAndRevokeAuthSessions(id, input);
    }

    @PutMapping("/users/{id}/warehouse-scopes")
    public AdminDtos.UserResponse replaceUserWarehouseScopes(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Scopes input) {
        return service.replaceUserWarehouseScopes(id, input);
    }

    @GetMapping("/roles")
    public List<AdminDtos.Role> listAvailableRoles() {
        return service.listAvailableRoles();
    }
}
