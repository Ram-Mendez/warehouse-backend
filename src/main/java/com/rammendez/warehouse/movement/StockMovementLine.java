package com.rammendez.warehouse.movement;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_movement_line")
public class StockMovementLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movement_id", nullable = false) StockMovement movement;
    @Column(name = "product_id", nullable = false) Long productId;
    @Column(name = "source_location_id") Long sourceLocationId;
    @Column(name = "target_location_id") Long targetLocationId;
    @Column(nullable = false, precision = 19, scale = 4) BigDecimal quantity;
    @Column(name = "unit_cost", precision = 19, scale = 4) BigDecimal unitCost;
    protected StockMovementLine() {}
    MovementDtos.Line response() {
        return new MovementDtos.Line(id, productId, sourceLocationId, targetLocationId, quantity, unitCost);
    }
}
