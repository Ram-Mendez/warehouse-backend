package com.rammendez.warehouse.warehouse;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.common.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    @Query("""
            select w from Warehouse w
            where exists (select 1 from WarehouseScope s where s.warehouseId = w.id and s.userId = :userId)
            """)
    Page<Warehouse> findWithinUserScope(long userId, Pageable pageable);

    @Query("select l from WarehouseLocation l where l.warehouse.id = :warehouseId and l.id = :id")
    Optional<WarehouseLocation> findLocation(long warehouseId, long id);

    @Query("""
            select l from WarehouseLocation l
            where l.warehouse.id = :warehouseId and l.code = 'DEFAULT' and l.active = true
            """)
    Optional<WarehouseLocation> findActiveDefaultLocation(long warehouseId);

    default WarehouseDtos.Response getWarehouse(long id) {
        return findById(id).orElseThrow(() -> BusinessException.missing("Warehouse")).toResponse();
    }

    default PageResponse<WarehouseDtos.Response> listWarehousesWithinUserScope(long userId, int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findWithinUserScope(userId, PageRequest.of(page, size, Sort.by("id")));
        return new PageResponse<>(result.map(Warehouse::toResponse).getContent(), result.getTotalElements(), page, size);
    }

    default WarehouseDtos.Location getWarehouseLocation(long warehouseId, long id) {
        return findLocation(warehouseId, id).orElseThrow(() -> BusinessException.missing("Location")).toResponse();
    }

    default WarehouseDtos.Location getActiveDefaultWarehouseLocation(long warehouseId) {
        return findActiveDefaultLocation(warehouseId)
                .orElseThrow(() -> BusinessException.missing("Default location")).toResponse();
    }
}
