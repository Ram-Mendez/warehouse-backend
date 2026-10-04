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
        access.warehouse(input.warehouseId(), true);
        if (!warehouses.get(input.warehouseId()).active()
                || !suppliers.get(input.supplierId()).active()) {
            throw BusinessException.conflict("Warehouse and supplier must be active");
        }
        UUID id = repository.insert(input, access.userId());
        audit.event("PURCHASE_CREATED", "purchase_order", id);
        return repository.get(id, false);
    }

    @PreAuthorize("hasAuthority('PERM_PURCHASE_READ')")
    public PurchaseDtos.Response get(UUID id) {
        var purchase = repository.get(id, false);
        access.warehouse(purchase.warehouseId(), false);
        return purchase;
    }

    @PreAuthorize("hasAuthority('PERM_PURCHASE_READ')")
    public PageResponse<PurchaseDtos.Summary> list(
            Long warehouseId, PurchaseStatus status, int page, int size) {
        if (warehouseId != null) {
            access.warehouse(warehouseId, false);
        }
        return repository.list(access.userId(), warehouseId, status, page, size);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response addLine(UUID id, PurchaseDtos.LineInput input) {
        var purchase = locked(id);
        require(purchase, PurchaseStatus.DRAFT);
        if (purchase.lines().size() >= 100) {
            throw BusinessException.conflict("At most 100 purchase lines are supported");
        }
        if (purchase.lines().stream().anyMatch(line -> line.productId() == input.productId())) {
            throw BusinessException.conflict("Product already has a purchase line");
        }
        products.requireActiveProduct(input.productId());
        repository.line(id, input);
        audit.event("PURCHASE_LINE_ADDED", "purchase_order", id);
        return repository.get(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response submit(UUID id) {
        var purchase = locked(id);
        require(purchase, PurchaseStatus.DRAFT);
        if (purchase.lines().isEmpty()) {
            throw BusinessException.conflict("Purchase requires at least one line");
        }
        return transition(id, PurchaseStatus.SUBMITTED);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_APPROVE')")
    public PurchaseDtos.Response approve(UUID id) {
        var purchase = locked(id);
        require(purchase, PurchaseStatus.SUBMITTED);
        repository.approve(id, access.userId());
        audit.event("PURCHASE_APPROVED", "purchase_order", id);
        return repository.get(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_RECEIVE')")
    public PurchaseDtos.Response receive(UUID id) {
        var purchase = locked(id);
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
        movements.receivePurchase(id, purchase.warehouseId(), lines);
        repository.received(id);
        audit.event("PURCHASE_RECEIVED", "purchase_order", id);
        return repository.get(id, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_CREATE')")
    public PurchaseDtos.Response cancel(UUID id) {
        var purchase = locked(id);
        if (purchase.status() != PurchaseStatus.DRAFT
                && purchase.status() != PurchaseStatus.SUBMITTED
                && purchase.status() != PurchaseStatus.APPROVED) {
            throw BusinessException.conflict(
                    "Purchase cannot be cancelled after receipt or cancellation");
        }
        return transition(id, PurchaseStatus.CANCELLED);
    }

    private PurchaseDtos.Response locked(UUID id) {
        var purchase = repository.get(id, true);
        access.warehouse(purchase.warehouseId(), true);
        return purchase;
    }

    private static void require(PurchaseDtos.Response purchase, PurchaseStatus status) {
        if (purchase.status() != status) {
            throw BusinessException.conflict("Purchase must be " + status);
        }
    }

    private PurchaseDtos.Response transition(UUID id, PurchaseStatus status) {
        repository.status(id, status);
        audit.event("PURCHASE_" + status, "purchase_order", id);
        return repository.get(id, false);
    }
}
