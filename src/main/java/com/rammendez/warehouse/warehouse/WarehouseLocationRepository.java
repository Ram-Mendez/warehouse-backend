package com.rammendez.warehouse.warehouse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseLocationRepository extends JpaRepository<WarehouseLocation, Long> {
    Page<WarehouseLocation> findByWarehouseId(long warehouseId, Pageable pageable);
}
