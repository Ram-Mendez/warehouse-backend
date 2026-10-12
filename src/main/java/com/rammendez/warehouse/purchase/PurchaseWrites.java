package com.rammendez.warehouse.purchase;

import java.util.UUID;

public interface PurchaseWrites {
    void insertPurchaseOrderLine(UUID id, PurchaseDtos.LineInput input);
    void markPurchaseOrderApprovedWithActor(UUID id, long actor);
}
