package com.rammendez.warehouse.product;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 80)
    String sku;

    @Column(length = 120)
    String barcode;

    @Column(nullable = false, length = 200)
    String name;

    @Column(columnDefinition = "text")
    String description;

    @Column(name = "category_id")
    Long categoryId;

    @Column(nullable = false, length = 30)
    String unit;

    @Column(name = "minimum_stock", nullable = false, precision = 19, scale = 4)
    BigDecimal minimumStock;

    @Column(nullable = false)
    boolean active;

    @Version
    @Column(nullable = false)
    Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected Product() {}

    @PrePersist
    void initializeCreationAndUpdateTimestamps() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void refreshUpdateTimestamp() {
        updatedAt = Instant.now();
    }
}
