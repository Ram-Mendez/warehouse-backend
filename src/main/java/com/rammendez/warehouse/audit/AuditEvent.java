package com.rammendez.warehouse.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "actor_user_id") Long actorUserId;
    @Column(name = "event_type", nullable = false, length = 120) String eventType;
    @Column(name = "entity_type", nullable = false, length = 120) String entityType;
    @Column(name = "entity_id", length = 160) String entityId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", nullable = false, columnDefinition = "jsonb") String eventData = "{}";
    @Column(name = "warehouse_id") Long warehouseId;
    @Column(name = "related_warehouse_id") Long relatedWarehouseId;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "occurred_at", nullable = false, updatable = false) Instant occurredAt;
    protected AuditEvent() {}
}
