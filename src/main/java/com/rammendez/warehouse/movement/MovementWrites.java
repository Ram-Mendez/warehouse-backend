package com.rammendez.warehouse.movement;

import java.util.UUID;

public interface MovementWrites {
    void insertStockMovementLine(UUID id, MovementDtos.Line line);
    void markStockMovementPostedWithActor(UUID id, long actor);
}
