package com.rammendez.warehouse.inventory;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "inventory_balance")
public class InventoryBalance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "product_id", nullable = false)
    Long productId;
    @Column(name = "warehouse_location_id", nullable = false)
    Long locationId;
    @Column(nullable = false, precision = 19, scale = 4)
    BigDecimal quantity = BigDecimal.ZERO;
    @Column(name = "reserved_quantity", nullable = false, precision = 19, scale = 4)
    BigDecimal reservedQuantity = BigDecimal.ZERO;
    @Version
    Long version;
    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;
    protected InventoryBalance() {}
}
