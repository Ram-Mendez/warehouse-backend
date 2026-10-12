package com.rammendez.warehouse.movement;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

public class MovementWritesImpl implements MovementWrites {
    private final EntityManager entityManager;
    public MovementWritesImpl(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override
    public void insertStockMovementLine(UUID id, MovementDtos.Line input) {
        var line = new StockMovementLine();
        line.movement = entityManager.getReference(StockMovement.class, id);
        line.productId = input.productId();
        line.sourceLocationId = input.sourceLocationId();
        line.targetLocationId = input.targetLocationId();
        line.quantity = input.quantity();
        line.unitCost = input.unitCost();
        entityManager.persist(line);
        // Lines must reach PostgreSQL before its posting trigger validates the header.
        entityManager.flush();
        // Return PostgreSQL NUMERIC scale/rounding, as subsequent reads do.
        entityManager.refresh(line);
    }

    @Override
    public void markStockMovementPostedWithActor(UUID id, long actor) {
        var movement = entityManager.find(StockMovement.class, id);
        movement.post(actor, entityManager.createQuery("select instant", Instant.class).getSingleResult());
        entityManager.flush();
    }
}
