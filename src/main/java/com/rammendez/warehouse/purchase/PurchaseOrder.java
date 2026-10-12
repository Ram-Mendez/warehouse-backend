package com.rammendez.warehouse.purchase;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {
    @Id UUID id;
    @Column(name = "order_number", nullable = false, length = 80) String orderNumber;
    @Column(name = "supplier_id", nullable = false) Long supplierId;
    @Column(name = "target_warehouse_id", nullable = false) Long warehouseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) PurchaseStatus status;
    @Column(name = "created_by", nullable = false) Long createdBy;
    @Column(name = "approved_by") Long approvedBy;
    @Column(name = "approved_at") Instant approvedAt;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @Version Long version;
    protected PurchaseOrder() {}

    void changeStatus(PurchaseStatus status) { this.status = status; }

    void approve(long actor, Instant approvedAt) {
        this.status = PurchaseStatus.APPROVED;
        this.approvedBy = actor;
        this.approvedAt = approvedAt;
    }

    PurchaseDtos.Response response(java.util.List<PurchaseDtos.Line> lines) {
        return new PurchaseDtos.Response(id, orderNumber, supplierId, warehouseId, status,
                createdBy, version, createdAt, lines);
    }
    PurchaseDtos.Summary summary() {
        return new PurchaseDtos.Summary(id, orderNumber, supplierId, warehouseId, status,
                createdBy, version, createdAt);
    }
}
