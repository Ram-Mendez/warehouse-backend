package com.rammendez.warehouse.movement;

import com.rammendez.warehouse.common.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface MovementRepository extends JpaRepository<StockMovement, UUID>, MovementWrites {
    default MovementDtos.Response getStockMovementWithLines(UUID id) {
        var movement = findById(id).orElseThrow(() -> BusinessException.missing("Movement"));
        return movement.response(findStockMovementLines(id));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from StockMovement m where m.id = :id")
    Optional<StockMovement> findLockedById(UUID id);

    default void lockStockMovement(UUID id) {
        findLockedById(id).orElseThrow(() -> BusinessException.missing("Movement"));
    }

    @Query("select m.id from StockMovement m where m.transferGroupId = :group order by m.id")
    List<UUID> findTransferMovementIdsInLockOrder(UUID group);

    @Query("select l from StockMovementLine l where l.movement.id in :ids order by l.id")
    List<StockMovementLine> findLines(Collection<UUID> ids);

    default List<MovementDtos.Line> findStockMovementLines(UUID id) {
        return findLines(List.of(id)).stream().map(StockMovementLine::response).toList();
    }

    default void insertDraftStockMovement(UUID id, MovementType type, Long source, Long target,
            String reason, String reference, long actor, UUID compensation, UUID group, UUID purchase) {
        var movement = new StockMovement();
        movement.id = id;
        movement.movementNumber = "MOV-" + id;
        movement.type = type;
        movement.status = MovementStatus.DRAFT;
        movement.sourceWarehouseId = source;
        movement.targetWarehouseId = target;
        movement.reason = reason;
        movement.reference = reference;
        movement.createdBy = actor;
        movement.compensatesMovementId = compensation;
        movement.transferGroupId = group;
        movement.purchaseOrderId = purchase;
        // Explicit flush preserves a DRAFT INSERT audit snapshot before POSTED UPDATE.
        saveAndFlush(movement);
    }

    @Query("select p.active from Product p where p.id = :id")
    Optional<Boolean> findProductActive(long id);

    default void requireActiveProduct(long id) {
        if (!findProductActive(id).orElseThrow(() -> BusinessException.missing("Product"))) {
            throw BusinessException.conflict("Product is inactive");
        }
    }

    @Query("""
            select m from StockMovement m
            where (m.sourceWarehouseId is null or exists (
                select 1 from WarehouseScope s where s.userId = :userId and s.warehouseId = m.sourceWarehouseId))
              and (m.targetWarehouseId is null or exists (
                select 1 from WarehouseScope s where s.userId = :userId and s.warehouseId = m.targetWarehouseId))
              and (:warehouseId is null or m.sourceWarehouseId = :warehouseId or m.targetWarehouseId = :warehouseId)
              and (:productId is null or exists (
                select 1 from StockMovementLine l where l.movement.id = m.id and l.productId = :productId))
              and (:type is null or m.type = :type)
            order by m.createdAt desc, m.id
            """)
    Page<StockMovement> findScopedMovements(long userId, Long warehouseId, Long productId,
            MovementType type, Pageable pageable);

    default PageResponse<MovementDtos.Response> listStockMovementsWithinUserScope(
            long userId, Long warehouseId, Long productId, MovementType type, int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findScopedMovements(userId, warehouseId, productId, type, PageRequest.of(page, size));
        var grouped = new HashMap<UUID, List<MovementDtos.Line>>();
        if (!result.isEmpty()) {
            for (var line : findLines(result.stream().map(m -> m.id).toList())) {
                grouped.computeIfAbsent(line.movement.getId(), ignored -> new ArrayList<>()).add(line.response());
            }
        }
        return new PageResponse<>(result.stream().map(m -> m.response(grouped.getOrDefault(m.id, List.of())))
                .toList(), result.getTotalElements(), page, size);
    }
}
