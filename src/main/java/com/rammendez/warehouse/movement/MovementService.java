package com.rammendez.warehouse.movement;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.inventory.InventoryRepository;
import com.rammendez.warehouse.security.AccessControl;
import com.rammendez.warehouse.warehouse.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class MovementService {
    private record Plan(
            MovementType type,
            Long source,
            Long target,
            String reason,
            String reference,
            UUID compensation,
            UUID group,
            UUID purchase,
            List<MovementDtos.Line> lines) {}

    private record StockKey(long locationId, long productId) implements Comparable<StockKey> {
        @Override
        public int compareTo(StockKey other) {
            int location = Long.compare(locationId, other.locationId);
            return location != 0 ? location : Long.compare(productId, other.productId);
        }
    }

    private final MovementRepository repository;
    private final InventoryRepository inventory;
    private final WarehouseRepository warehouses;
    private final AccessControl access;
    private final AuditService audit;

    public MovementService(
            MovementRepository repository,
            InventoryRepository inventory,
            WarehouseRepository warehouses,
            AccessControl access,
            AuditService audit) {
        this.repository = repository;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    public MovementDtos.Response receiveStockAndPostReceiptMovement(MovementDtos.StockInput input) {
        return postSingleStockMovementAndApplyQuantityChange(input, MovementType.RECEIPT, true);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    public MovementDtos.Response returnStockAndPostReturnMovement(MovementDtos.StockInput input) {
        return postSingleStockMovementAndApplyQuantityChange(input, MovementType.RETURN, true);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_STOCK_ISSUE')")
    public MovementDtos.Response issueAvailableStockAndPostIssueMovement(MovementDtos.StockInput input) {
        return postSingleStockMovementAndApplyQuantityChange(input, MovementType.ISSUE, false);
    }

    private MovementDtos.Response postSingleStockMovementAndApplyQuantityChange(
            MovementDtos.StockInput input, MovementType type, boolean incoming) {
        requirePositiveQuantityWithinSupportedPrecision(input.quantity());
        long location = requireWritableWarehouseAndResolveActiveLocationId(input.warehouseId(), input.locationId());
        var line =
                new MovementDtos.Line(
                        0,
                        input.productId(),
                        incoming ? null : location,
                        incoming ? location : null,
                        input.quantity(),
                        null);
        var plan =
                new Plan(
                        type,
                        incoming ? null : input.warehouseId(),
                        incoming ? input.warehouseId() : null,
                        input.reason(),
                        input.reference(),
                        null,
                        null,
                        null,
                        List.of(line));
        return validateLockApplyAndPostStockMovementPlans(List.of(plan)).movements().getFirst();
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_INVENTORY_ADJUST')")
    public MovementDtos.Response applySignedStockAdjustmentAndPostMovement(MovementDtos.Adjustment input) {
        if (input.delta() == null || input.delta().signum() == 0) {
            throw BusinessException.invalid("Adjustment delta must be nonzero");
        }
        boolean incoming = input.delta().signum() > 0;
        return postSingleStockMovementAndApplyQuantityChange(
                new MovementDtos.StockInput(
                        input.warehouseId(),
                        input.productId(),
                        input.locationId(),
                        input.delta().abs(),
                        input.reason(),
                        null),
                MovementType.ADJUSTMENT,
                incoming);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_STOCK_TRANSFER')")
    public MovementDtos.Result transferStockAndPostLinkedMovementsAtomically(MovementDtos.Transfer input) {
        if (input.sourceWarehouseId() == input.destinationWarehouseId()) {
            throw BusinessException.invalid("Transfer warehouses must differ");
        }
        requirePositiveQuantityWithinSupportedPrecision(input.quantity());
        long source = requireWritableWarehouseAndResolveActiveLocationId(input.sourceWarehouseId(), input.sourceLocationId());
        long target = requireWritableWarehouseAndResolveActiveLocationId(input.destinationWarehouseId(), input.destinationLocationId());
        UUID group = UUID.randomUUID();
        var out =
                new Plan(
                        MovementType.TRANSFER_OUT,
                        input.sourceWarehouseId(),
                        input.destinationWarehouseId(),
                        input.reason(),
                        input.reference(),
                        null,
                        group,
                        null,
                        List.of(
                                new MovementDtos.Line(
                                        0,
                                        input.productId(),
                                        source,
                                        null,
                                        input.quantity(),
                                        null)));
        var in =
                new Plan(
                        MovementType.TRANSFER_IN,
                        input.sourceWarehouseId(),
                        input.destinationWarehouseId(),
                        input.reason(),
                        input.reference(),
                        null,
                        group,
                        null,
                        List.of(
                                new MovementDtos.Line(
                                        0,
                                        input.productId(),
                                        null,
                                        target,
                                        input.quantity(),
                                        null)));
        var result = validateLockApplyAndPostStockMovementPlans(List.of(out, in));
        audit.recordAuditEventWithActorAndWarehouseReferences("TRANSFER_COMPLETED", "transfer", group);
        return result;
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_INVENTORY_ADJUST')")
    public MovementDtos.Result reversePostedMovementOrTransferWithCompensatingMovements(UUID id, String reason) {
        var original = repository.getStockMovementWithLines(id);
        var ids =
                original.transferGroupId() == null
                        ? List.of(id)
                        : repository.findTransferMovementIdsInLockOrder(original.transferGroupId());
        ids.forEach(repository::lockStockMovement);
        var plans = new ArrayList<Plan>();
        for (UUID originalId : ids) {
            var movement = repository.getStockMovementWithLines(originalId);
            if (movement.status() != MovementStatus.POSTED
                    || movement.type() == MovementType.COMPENSATION) {
                throw BusinessException.conflict(
                        "Only original posted movements can be compensated");
            }
            requireScopesForAllMovementWarehouses(movement, true);
            var lines =
                    movement.lines().stream()
                            .map(
                                    line ->
                                            new MovementDtos.Line(
                                                    0,
                                                    line.productId(),
                                                    line.targetLocationId(),
                                                    line.sourceLocationId(),
                                                    line.quantity(),
                                                    line.unitCost()))
                            .toList();
            plans.add(
                    new Plan(
                            MovementType.COMPENSATION,
                            movement.targetWarehouseId(),
                            movement.sourceWarehouseId(),
                            reason,
                            "Compensates " + originalId,
                            originalId,
                            null,
                            null,
                            lines));
        }
        var result = validateLockApplyAndPostStockMovementPlans(plans);
        audit.recordAuditEventWithActorAndWarehouseReferences("MOVEMENT_COMPENSATED", "stock_movement", id);
        return result;
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PURCHASE_RECEIVE')")
    public MovementDtos.Response receivePurchaseLinesIntoDefaultLocationAndPostReceipt(
            UUID purchaseId, long warehouseId, List<MovementDtos.Line> orderedLines) {
        long location = requireWritableWarehouseAndResolveActiveLocationId(warehouseId, null);
        var lines =
                orderedLines.stream()
                        .map(
                                l ->
                                        new MovementDtos.Line(
                                                0,
                                                l.productId(),
                                                null,
                                                location,
                                                l.quantity(),
                                                l.unitCost()))
                        .toList();
        return validateLockApplyAndPostStockMovementPlans(List.of(
                        new Plan(
                                MovementType.RECEIPT,
                                null,
                                warehouseId,
                                "Purchase receipt",
                                purchaseId.toString(),
                                null,
                                null,
                                purchaseId,
                                lines)))
                .movements()
                .getFirst();
    }

    @PreAuthorize("hasAuthority('PERM_MOVEMENT_READ')")
    public MovementDtos.Response getStockMovementWithLines(UUID id) {
        var movement = repository.getStockMovementWithLines(id);
        requireScopesForAllMovementWarehouses(movement, false);
        return movement;
    }

    @PreAuthorize("hasAuthority('PERM_MOVEMENT_READ')")
    public PageResponse<MovementDtos.Response> listStockMovementsWithinUserScope(
            Long warehouseId, Long productId, MovementType type, int page, int size) {
        if (warehouseId != null) {
            access.requireWarehouseScope(warehouseId, false);
        }
        return repository.listStockMovementsWithinUserScope(access.getAuthenticatedUserId(), warehouseId, productId, type, page, size);
    }

    private void requireScopesForAllMovementWarehouses(MovementDtos.Response movement, boolean write) {
        if (movement.sourceWarehouseId() != null) {
            access.requireWarehouseScope(movement.sourceWarehouseId(), write);
        }
        if (movement.targetWarehouseId() != null) {
            access.requireWarehouseScope(movement.targetWarehouseId(), write);
        }
    }

    private long requireWritableWarehouseAndResolveActiveLocationId(long warehouseId, Long locationId) {
        requireWritableActiveWarehouse(warehouseId);
        var location =
                locationId == null
                        ? warehouses.getActiveDefaultWarehouseLocation(warehouseId)
                        : warehouses.getWarehouseLocation(warehouseId, locationId);
        if (!location.active()) {
            throw BusinessException.conflict("Location is inactive");
        }
        return location.id();
    }

    private void requireWritableActiveWarehouse(long warehouseId) {
        access.requireWarehouseScope(warehouseId, true);
        if (!warehouses.getWarehouse(warehouseId).active()) {
            throw BusinessException.conflict("Warehouse is inactive");
        }
    }

    private MovementDtos.Result validateLockApplyAndPostStockMovementPlans(List<Plan> plans) {
        Map<StockKey, BigDecimal> deltas = new TreeMap<>();
        for (var plan : plans) {
            if (plan.source() != null) {
                requireWritableActiveWarehouse(plan.source());
            }
            if (plan.target() != null) {
                requireWritableActiveWarehouse(plan.target());
            }
            for (var line : plan.lines()) {
                requirePositiveQuantityWithinSupportedPrecision(line.quantity());
                repository.requireActiveProduct(line.productId());
                if (line.sourceLocationId() != null) {
                    requireWritableWarehouseAndResolveActiveLocationId(plan.source(), line.sourceLocationId());
                    accumulateStockQuantityDelta(
                            deltas,
                            new StockKey(line.sourceLocationId(), line.productId()),
                            line.quantity().negate());
                }
                if (line.targetLocationId() != null) {
                    requireWritableWarehouseAndResolveActiveLocationId(plan.target(), line.targetLocationId());
                    accumulateStockQuantityDelta(
                            deltas,
                            new StockKey(line.targetLocationId(), line.productId()),
                            line.quantity());
                }
            }
        }
        for (var entry : deltas.entrySet()) {
            var key = entry.getKey();
            inventory.createInventoryBalanceIfMissing(key.productId(), key.locationId());
            var available = inventory.lockInventoryBalanceAndGetAvailableQuantity(key.productId(), key.locationId());
            if (available.add(entry.getValue()).signum() < 0) {
                throw BusinessException.conflict("Insufficient available stock");
            }
        }
        for (var entry : deltas.entrySet()) {
            var key = entry.getKey();
            inventory.applyQuantityDeltaAndAdvanceBalanceVersion(key.productId(), key.locationId(), entry.getValue());
        }
        audit.setTransactionLocalAuthenticatedAuditActor();
        var result = new ArrayList<MovementDtos.Response>();
        for (var plan : plans) {
            UUID id = UUID.randomUUID();
            repository.insertDraftStockMovement(
                    id,
                    plan.type(),
                    plan.source(),
                    plan.target(),
                    plan.reason(),
                    plan.reference(),
                    access.getAuthenticatedUserId(),
                    plan.compensation(),
                    plan.group(),
                    plan.purchase());
            plan.lines().forEach(line -> repository.insertStockMovementLine(id, line));
            repository.markStockMovementPostedWithActor(id, access.getAuthenticatedUserId());
            audit.recordAuditEventWithActorAndWarehouseReferences("MOVEMENT_POSTED", "stock_movement", id);
            result.add(repository.getStockMovementWithLines(id));
        }
        return new MovementDtos.Result(result);
    }

    private static void accumulateStockQuantityDelta(Map<StockKey, BigDecimal> deltas, StockKey key, BigDecimal delta) {
        deltas.merge(key, delta, BigDecimal::add);
    }

    private static void requirePositiveQuantityWithinSupportedPrecision(BigDecimal quantity) {
        if (quantity == null
                || quantity.signum() <= 0
                || quantity.scale() > 4
                || quantity.precision() - quantity.scale() > 15) {
            throw BusinessException.invalid(
                    "Quantity must be positive with at most 15 integer and 4 decimal digits");
        }
    }
}
