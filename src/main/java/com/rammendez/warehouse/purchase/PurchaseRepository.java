package com.rammendez.warehouse.purchase;

import com.rammendez.warehouse.common.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface PurchaseRepository extends JpaRepository<PurchaseOrder, UUID>, PurchaseWrites {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findLockedById(UUID id);

    @Query("select l from PurchaseOrderLine l where l.purchase.id = :id order by l.id")
    List<PurchaseOrderLine> findLines(UUID id);

    default PurchaseDtos.Response getPurchaseOrderWithLinesAndOptionalLock(UUID id, boolean lock) {
        var purchase = (lock ? findLockedById(id) : findById(id))
                .orElseThrow(() -> BusinessException.missing("Purchase order"));
        return purchase.response(findLines(id).stream().map(PurchaseOrderLine::response).toList());
    }

    default UUID insertDraftPurchaseOrder(PurchaseDtos.Input input, long actor) {
        var purchase = new PurchaseOrder();
        purchase.id = UUID.randomUUID();
        purchase.orderNumber = input.orderNumber().trim();
        purchase.supplierId = input.supplierId();
        purchase.warehouseId = input.warehouseId();
        purchase.status = PurchaseStatus.DRAFT;
        purchase.createdBy = actor;
        return saveAndFlush(purchase).id;
    }

    default void updatePurchaseOrderStatusAndAdvanceVersion(UUID id, PurchaseStatus status) {
        var purchase = findById(id).orElseThrow(() -> BusinessException.missing("Purchase order"));
        purchase.changeStatus(status);
        saveAndFlush(purchase);
    }

    default void markAllPurchaseLinesAndOrderReceived(UUID id) {
        findLines(id).forEach(line -> line.receivedQuantity = line.orderedQuantity);
        updatePurchaseOrderStatusAndAdvanceVersion(id, PurchaseStatus.RECEIVED);
    }

    @Query("""
            select p from PurchaseOrder p
            where exists (select 1 from WarehouseScope s
                          where s.userId = :actor and s.warehouseId = p.warehouseId)
              and (:warehouseId is null or p.warehouseId = :warehouseId)
              and (:status is null or p.status = :status)
            order by p.createdAt desc, p.id
            """)
    Page<PurchaseOrder> findScopedOrders(long actor, Long warehouseId, PurchaseStatus status, Pageable pageable);

    default PageResponse<PurchaseDtos.Summary> listPurchaseOrdersWithinUserScope(
            long actor, Long warehouseId, PurchaseStatus status, int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findScopedOrders(actor, warehouseId, status, PageRequest.of(page, size));
        return new PageResponse<>(result.stream().map(PurchaseOrder::summary).toList(), result.getTotalElements(), page, size);
    }
}
