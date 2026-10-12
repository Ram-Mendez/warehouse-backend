package com.rammendez.warehouse.security;

import jakarta.persistence.*;

@Entity
@Table(name = "security_permission")
public class SecurityPermission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String description;

    protected SecurityPermission() {}
}
