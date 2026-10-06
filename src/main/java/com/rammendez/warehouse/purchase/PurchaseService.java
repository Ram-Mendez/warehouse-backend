package com.rammendez.warehouse.purchase;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.movement.*;
import com.rammendez.warehouse.security.AccessControl;
import com.rammendez.warehouse.supplier.SupplierRepository;
import com.rammendez.warehouse.warehouse.WarehouseRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PurchaseService {
    private final PurchaseRepository repository;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final MovementRepository products;
    private final MovementService movements;
    private final AccessControl access;
    private final AuditService audit;

    public PurchaseService(
            PurchaseRepository repository,
            SupplierRepository suppliers,
            WarehouseRepository warehouses,
            MovementRepository products,
            MovementService movements,
            AccessControl access,
            AuditService audit) {
        this.repository = repository;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.products = products;
        this.movements = movements;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response create(PurchaseDtos.Input input) {
        access.requireWarehouseScope(input.warehouseId(), true);
        if (!warehouses.getWarehouse(input.warehouseId()).active()
                || !suppliers.getSupplier(input.supplierId()).active()) {
            throw BusinessException.conflict("Warehouse and supplier must be active");
        }
        UUID id = repository.insertDraftPurchaseOrder(input, access.getAuthenticatedUserId());
        audit.recordAuditEventWithActorAndWarehouseReferences("PURCHASE_CREATED", "purchase_order", id);
        return repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
    }

    @PreAuthorize("hasAuthority('PERM_PURCHASE_READ')")
    public PurchaseDtos.Response getPurchaseOrderWithLines(UUID id) {
        var purchase = repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
        access.requireWarehouseScope(purchase.warehouseId(), false);
        return purchase;
    }

    @PreAuthorize("hasAuthority('PERM_PURCHASE_READ')")
    public PageResponse<PurchaseDtos.Summary> listPurchaseOrdersWithinUserScope(
            Long warehouseId, PurchaseStatus status, int page, int size) {
        if (warehouseId != null) {
            access.requireWarehouseScope(warehouseId, false);
        }
        return repository.listPurchaseOrdersWithinUserScope(access.getAuthenticatedUserId(), warehouseId, status, page, size);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response addLine(UUID id, PurchaseDtos.LineInput input) {
        var purchase = lockPurchaseOrderAndRequireWarehouseWriteScope(id);
        requirePurchaseOrderStatus(purchase, PurchaseStatus.DRAFT);
        if (purchase.lines().size() >= 100) {
            throw BusinessException.conflict("At most 100 purchase lines are supported");
        }
        if (purchase.lines().stream().anyMatch(line -> line.productId() == input.productId())) {
            throw BusinessException.conflict("Product already has a purchase line");
        }
        products.requireActiveProduct(input.productId());
        repository.insertPurchaseOrderLine(id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("PURCHASE_LINE_ADDED", "purchase_order", id);
        return repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response submit(UUID id) {
        var purchase = lockPurchaseOrderAndRequireWarehouseWriteScope(id);
        requirePurchaseOrderStatus(purchase, PurchaseStatus.DRAFT);
        if (purchase.lines().isEmpty()) {
            throw BusinessException.conflict("Purchase requires at least one line");
        }
        return changePurchaseOrderStatusAndRecordAuditEvent(id, PurchaseStatus.SUBMITTED);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_APPROVE')")
    public PurchaseDtos.Response approve(UUID id) {
        var purchase = lockPurchaseOrderAndRequireWarehouseWriteScope(id);
        requirePurchaseOrderStatus(purchase, PurchaseStatus.SUBMITTED);
        repository.markPurchaseOrderApprovedWithActor(id, access.getAuthenticatedUserId());
        audit.recordAuditEventWithActorAndWarehouseReferences("PURCHASE_APPROVED", "purchase_order", id);
        return repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_RECEIVE')")
    public PurchaseDtos.Response receiveAllPurchaseLinesAndMarkOrderReceived(UUID id) {
        var purchase = repository.getPurchaseOrderWithLinesAndOptionalLock(id, true);
        access.requireWarehouseScope(purchase.warehouseId(), true);
        if (purchase.status() != PurchaseStatus.SUBMITTED
                && purchase.status() != PurchaseStatus.APPROVED) {
            throw BusinessException.conflict(
                    "Only submitted or approved purchases can be received once");
        }
        if (purchase.lines().isEmpty()
                || purchase.lines().stream()
                        .anyMatch(line -> line.receivedQuantity().signum() != 0)) {
            throw BusinessException.conflict("Full receipt requires unreceived lines");
        }
        var lines =
                purchase.lines().stream()
                        .map(
                                line ->
                                        new MovementDtos.Line(
                                                0,
                                                line.productId(),
                                                null,
                                                null,
                                                line.orderedQuantity(),
                                                line.unitCost()))
                        .toList();
        movements.receivePurchaseLinesIntoDefaultLocationAndPostReceipt(id, purchase.warehouseId(), lines);
        repository.markAllPurchaseLinesAndOrderReceived(id);
        audit.recordAuditEventWithActorAndWarehouseReferences("PURCHASE_RECEIVED", "purchase_order", id);
        return repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response cancel(UUID id) {
        var purchase = lockPurchaseOrderAndRequireWarehouseWriteScope(id);
        if (purchase.status() != PurchaseStatus.DRAFT
                && purchase.status() != PurchaseStatus.SUBMITTED
                && purchase.status() != PurchaseStatus.APPROVED) {
            throw BusinessException.conflict(
                    "Purchase cannot be cancelled after receipt or cancellation");
        }
        return changePurchaseOrderStatusAndRecordAuditEvent(id, PurchaseStatus.CANCELLED);
    }

    private PurchaseDtos.Response lockPurchaseOrderAndRequireWarehouseWriteScope(UUID id) {
        var purchase = repository.getPurchaseOrderWithLinesAndOptionalLock(id, true);
        access.requireWarehouseScope(purchase.warehouseId(), true);
        return purchase;
    }

    private static void requirePurchaseOrderStatus(PurchaseDtos.Response purchase, PurchaseStatus status) {
        if (purchase.status() != status) {
            throw BusinessException.conflict("Purchase must be " + status);
        }
    }

    private PurchaseDtos.Response changePurchaseOrderStatusAndRecordAuditEvent(UUID id, PurchaseStatus status) {
        repository.updatePurchaseOrderStatusAndAdvanceVersion(id, status);
        audit.recordAuditEventWithActorAndWarehouseReferences("PURCHASE_" + status, "purchase_order", id);
        return repository.getPurchaseOrderWithLinesAndOptionalLock(id, false);
    }
}
