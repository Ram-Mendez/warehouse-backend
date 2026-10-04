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
    public PageResponse<AdminDtos.UserSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/users/{id}")
    public AdminDtos.UserResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.UserResponse create(@Valid @RequestBody AdminDtos.Create input) {
        return service.create(input);
    }

    @PatchMapping("/users/{id}")
    public AdminDtos.UserResponse update(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Update input) {
        return service.update(id, input);
    }

    @PutMapping("/users/{id}/roles")
    public AdminDtos.UserResponse roles(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Roles input) {
        return service.roles(id, input);
    }

    @PutMapping("/users/{id}/warehouse-scopes")
    public AdminDtos.UserResponse scopes(
            @PathVariable long id, @Valid @RequestBody AdminDtos.Scopes input) {
        return service.scopes(id, input);
    }

    @GetMapping("/roles")
    public List<AdminDtos.Role> roles() {
        return service.roles();
    }
}
