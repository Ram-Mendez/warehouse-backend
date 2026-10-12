package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.security.AccessControl;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditService {
    private final EntityManager entityManager;
    private final AuditRepository repository;
    private final AccessControl access;

    public AuditService(EntityManager entityManager, AuditRepository repository, AccessControl access) {
        this.entityManager = entityManager;
        this.repository = repository;
        this.access = access;
    }

    public void setTransactionLocalAuthenticatedAuditActor() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit actor requires transaction");
        }
        // Native JPA runs on the same transaction/connection as Hibernate's ledger flushes.
        // true prevents the actor identity from leaking through the connection pool.
        entityManager.createNativeQuery("select set_config('app.current_user_id', :actor, true)", String.class)
                .setParameter("actor", Long.toString(access.getAuthenticatedUserId())).getSingleResult();
    }

    public void recordAuditEventWithActorAndWarehouseReferences(String type, String entity, Object id) {
        var event = new AuditEvent();
        event.actorUserId = access.getAuthenticatedUserId();
        event.eventType = type;
        event.entityType = entity;
        event.entityId = id.toString();
        switch (entity) {
            case "warehouse" -> event.warehouseId = Long.valueOf(id.toString());
            case "warehouse_location" -> event.warehouseId = entityManager.createQuery(
                    "select l.warehouse.id from WarehouseLocation l where l.id = :id", Long.class)
                    .setParameter("id", id).getSingleResult();
            case "purchase_order" -> event.warehouseId = entityManager.createQuery(
                    "select p.warehouseId from PurchaseOrder p where p.id = :id", Long.class)
                    .setParameter("id", id).getSingleResult();
            case "stock_movement", "transfer" -> {
                String predicate = entity.equals("transfer") ? "m.transferGroupId = :id" : "m.id = :id";
                var rows = entityManager.createQuery(
                        "select m.sourceWarehouseId, m.targetWarehouseId from StockMovement m where " + predicate,
                        Object[].class).setParameter("id", id).setMaxResults(1).getResultList();
                if (!rows.isEmpty()) {
                    event.warehouseId = (Long) rows.getFirst()[0];
                    event.relatedWarehouseId = (Long) rows.getFirst()[1];
                }
            }
            default -> {}
        }
        repository.saveAndFlush(event);
    }
}
