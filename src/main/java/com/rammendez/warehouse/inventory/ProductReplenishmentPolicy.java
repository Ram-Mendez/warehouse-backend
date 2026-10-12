package com.rammendez.warehouse.inventory;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "product_replenishment_policy")
public class ProductReplenishmentPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "product_id", nullable = false)
    Long productId;

    @Column(name = "warehouse_id", nullable = false)
    Long warehouseId;

    @Column(name = "minimum_stock", nullable = false, precision = 19, scale = 4)
    BigDecimal minimumStock;

    @Column(name = "target_stock", nullable = false, precision = 19, scale = 4)
    BigDecimal targetStock;

    @Column(name = "active", nullable = false)
    boolean active;

    @Version
    @Column(name = "version", nullable = false)
    Long version;

    protected ProductReplenishmentPolicy() {}
}