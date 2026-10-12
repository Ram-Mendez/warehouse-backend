package com.rammendez.warehouse.warehouse;

import jakarta.persistence.*;

@Entity
@Table(name = "warehouse")
public class Warehouse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 50)
    String code;

    @Column(nullable = false, length = 160)
    String name;

    @Column(nullable = false)
    boolean active;

    protected Warehouse() {}

    public Long getId() { return id; }

    WarehouseDtos.Response toResponse() {
        return new WarehouseDtos.Response(id, code, name, active);
    }
}
