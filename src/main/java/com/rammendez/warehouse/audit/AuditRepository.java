package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.PageResponse;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<AuditEvent, Long>, AuditHistory {
    public record MovementAudit(
            long id,
            UUID movementId,
            String operation,
            Long actorUserId,
            String dbUser,
            JsonNode oldRow,
            JsonNode newRow,
            Instant changedAt) {}

    public record Event(
            long id,
            Long actorUserId,
            String eventType,
            String entityType,
            String entityId,
            JsonNode eventData,
            Instant occurredAt) {}


    @Query("""
            select e from AuditEvent e
            where (e.warehouseId is null or exists (select 1 from WarehouseScope s
                where s.userId = :userId and s.warehouseId = e.warehouseId))
              and (e.relatedWarehouseId is null or exists (select 1 from WarehouseScope s
                where s.userId = :userId and s.warehouseId = e.relatedWarehouseId))
              and (e.entityType <> 'security_user' or :admin = true)
              and (e.entityType <> 'contact_message' or :contact = true)
              and (:entityType is null or e.entityType = :entityType)
              and (:entityId is null or e.entityId = :entityId)
            order by e.occurredAt desc, e.id desc
            """)
    Page<AuditEvent> findScopedEvents(long userId, boolean admin, boolean contact,
            String entityType, String entityId, Pageable pageable);
}
