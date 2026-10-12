package com.rammendez.warehouse.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

// Written exclusively by the PostgreSQL trigger, including historical snapshots.
@Entity
@Immutable
@Table(name = "stock_movement_audit")
public class StockMovementAudit {
    @Id Long id;
    @Column(name = "movement_id") UUID movementId;
    @Column(length = 10) String operation;
    @Column(name = "actor_user_id") Long actorUserId;
    @Column(name = "db_user", length = 120) String dbUser;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "old_row", columnDefinition = "jsonb") String oldRow;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "new_row", columnDefinition = "jsonb") String newRow;
    @Column(name = "changed_at") Instant changedAt;
    protected StockMovementAudit() {}
}
