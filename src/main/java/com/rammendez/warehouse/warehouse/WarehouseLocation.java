package com.rammendez.warehouse.warehouse;

import jakarta.persistence.*;

@Entity
@Table(name = "warehouse_location")
public class WarehouseLocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    Warehouse warehouse;

    @Column(nullable = false, length = 80)
    String code;

    @Column(length = 255)
    String description;

    @Column(nullable = false)
    boolean active;

    protected WarehouseLocation() {}

    public Long getId() { return id; }

    WarehouseDtos.Location toResponse() {
        return new WarehouseDtos.Location(id, warehouse.getId(), code, description, active);
    }
}
