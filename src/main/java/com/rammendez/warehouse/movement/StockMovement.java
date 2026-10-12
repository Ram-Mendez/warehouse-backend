package com.rammendez.warehouse.movement;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movement")
public class StockMovement {
    @Id UUID id;
    @Column(name = "movement_number", nullable = false, length = 80)
    String movementNumber;
    @Enumerated(EnumType.STRING) @Column(name = "movement_type", nullable = false, length = 30)
    MovementType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    MovementStatus status;
    @Column(name = "source_warehouse_id") Long sourceWarehouseId;
    @Column(name = "target_warehouse_id") Long targetWarehouseId;
    @Column(length = 500) String reason;
    @Column(name = "external_reference", length = 160) String reference;
    @Column(name = "created_by", nullable = false) Long createdBy;
    @Column(name = "posted_by") Long postedBy;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @Column(name = "posted_at") Instant postedAt;
    @Column(name = "compensates_movement_id") UUID compensatesMovementId;
    @Column(name = "transfer_group_id") UUID transferGroupId;
    @Column(name = "purchase_order_id") UUID purchaseOrderId;
    @Version Long version;
    protected StockMovement() {}
    public UUID getId() { return id; }

    void post(long actor, Instant postedAt) {
        this.status = MovementStatus.POSTED;
        this.postedBy = actor;
        this.postedAt = postedAt;
    }

    MovementDtos.Response response(java.util.List<MovementDtos.Line> lines) {
        return new MovementDtos.Response(id, movementNumber, type, status,
                sourceWarehouseId, targetWarehouseId, reason, reference, createdBy, postedBy,
                createdAt, postedAt, compensatesMovementId, transferGroupId, purchaseOrderId, lines);
    }
}
