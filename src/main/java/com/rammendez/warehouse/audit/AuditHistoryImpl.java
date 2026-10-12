package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.PageResponse;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

public class AuditHistoryImpl implements AuditHistory {
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    public AuditHistoryImpl(EntityManager entityManager, ObjectMapper mapper) {
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    // Both snapshots must remain visible only to users authorized for every historical warehouse.
    // Native SQL is confined to PostgreSQL JSONB extraction; entities still map the result.
    private static final String FILTER = """
            from stock_movement_audit a
            where (cast(:movementId as uuid) is null or a.movement_id = :movementId)
              and not exists (
                select 1 from (values
                    (a.old_row ->> 'source_warehouse_id'), (a.old_row ->> 'target_warehouse_id'),
                    (a.new_row ->> 'source_warehouse_id'), (a.new_row ->> 'target_warehouse_id')
                ) as referenced(warehouse_id)
                where referenced.warehouse_id is not null
                  and not exists (select 1 from security_user_warehouse_scope s
                    where s.user_id = :userId and s.warehouse_id = cast(referenced.warehouse_id as bigint))
              )
            """;

    @Override
    public PageResponse<AuditRepository.MovementAudit> listMovementAuditHistoryWithinUserScope(
            long userId, UUID movementId, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var count = entityManager.unwrap(org.hibernate.Session.class).createNativeQuery("select count(*) " + FILTER, Long.class)
                .setParameter("userId", userId).setParameter("movementId", movementId).getSingleResult();
        var rows = entityManager.unwrap(org.hibernate.Session.class).createNativeQuery(
                "select a.* " + FILTER + " order by a.changed_at desc, a.id desc", StockMovementAudit.class)
                .setParameter("userId", userId).setParameter("movementId", movementId)
                .setFirstResult(offset).setMaxResults(size).getResultList();
        return new PageResponse<>(rows.stream().map(a -> new AuditRepository.MovementAudit(
                a.id, a.movementId, a.operation, a.actorUserId, a.dbUser,
                a.oldRow == null ? null : mapper.readTree(a.oldRow),
                a.newRow == null ? null : mapper.readTree(a.newRow), a.changedAt)).toList(), count, page, size);
    }
}
