package com.rammendez.warehouse.purchase;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false) PurchaseOrder purchase;
    @Column(name = "product_id", nullable = false) Long productId;
    @Column(name = "ordered_quantity", nullable = false, precision = 19, scale = 4) BigDecimal orderedQuantity;
    @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4) BigDecimal receivedQuantity = BigDecimal.ZERO;
    @Column(name = "unit_cost", precision = 19, scale = 4) BigDecimal unitCost;
    protected PurchaseOrderLine() {}
    PurchaseDtos.Line response() {
        return new PurchaseDtos.Line(id, productId, orderedQuantity, receivedQuantity, unitCost);
    }
}
