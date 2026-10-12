package com.rammendez.warehouse.purchase;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

public class PurchaseWritesImpl implements PurchaseWrites {
    private final EntityManager entityManager;
    public PurchaseWritesImpl(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override
    public void insertPurchaseOrderLine(UUID id, PurchaseDtos.LineInput input) {
        var line = new PurchaseOrderLine();
        line.purchase = entityManager.getReference(PurchaseOrder.class, id);
        line.productId = input.productId();
        line.orderedQuantity = input.quantity();
        line.unitCost = input.unitCost();
        entityManager.persist(line);
        entityManager.flush();
        // Return PostgreSQL NUMERIC scale/rounding, as subsequent reads do.
        entityManager.refresh(line);
    }

    @Override
    public void markPurchaseOrderApprovedWithActor(UUID id, long actor) {
        var purchase = entityManager.find(PurchaseOrder.class, id);
        purchase.approve(actor, entityManager.createQuery("select instant", Instant.class).getSingleResult());
        entityManager.flush();
    }
}
