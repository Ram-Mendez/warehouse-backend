package com.rammendez.warehouse.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "security_user")
public class SecurityUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String email;
    @Column(name = "password_hash")
    private String passwordHash;
    private boolean enabled = true;
    private boolean locked;
    @Column(name = "auth_version")
    private long authVersion;
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;
    @ManyToMany
    @JoinTable(name = "security_user_role", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<SecurityRole> roles = new LinkedHashSet<>();

    protected SecurityUser() {}

    public SecurityUser(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }

    public UserAccount toAccount() {
        return new UserAccount(id, username, email, passwordHash, enabled, locked, authVersion);
    }

    public AdminDtos.UserSummary toSummary() {
        return new AdminDtos.UserSummary(id, username, email, enabled, locked);
    }

    public void update(AdminDtos.Update input, String hash) {
        if (input.email() != null) email = input.email();
        if (input.enabled() != null) enabled = input.enabled();
        if (input.locked() != null) locked = input.locked();
        if (hash != null) passwordHash = hash;
        advanceAuthVersion();
    }

    public void replaceRoles(Set<SecurityRole> replacement) {
        roles.clear();
        roles.addAll(replacement);
        advanceAuthVersion();
    }

    private void advanceAuthVersion() {
        authVersion++;
        updatedAt = Instant.now();
    }
}
