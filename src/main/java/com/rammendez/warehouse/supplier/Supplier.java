package com.rammendez.warehouse.supplier;

import jakarta.persistence.*;

@Entity
@Table(name = "supplier")
public class Supplier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 60)
    String code;

    @Column(nullable = false, length = 180)
    String name;

    @Column(length = 255)
    String email;

    @Column(length = 80)
    String phone;

    @Column(nullable = false)
    boolean active;

    protected Supplier() {}

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }

    SupplierDtos.Response toResponse() {
        return new SupplierDtos.Response(id, code, name, email, phone, active);
    }
}
