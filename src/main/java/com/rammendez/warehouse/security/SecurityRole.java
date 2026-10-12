package com.rammendez.warehouse.security;

import jakarta.persistence.*;
import java.util.Set;

@Entity
@Table(name = "security_role")
public class SecurityRole {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String description;
    @ManyToMany
    @JoinTable(name = "security_role_permission", joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<SecurityPermission> permissions;

    protected SecurityRole() {}
    public Long getId() { return id; }
    public AdminDtos.Role toResponse() { return new AdminDtos.Role(id, code, description); }
}
