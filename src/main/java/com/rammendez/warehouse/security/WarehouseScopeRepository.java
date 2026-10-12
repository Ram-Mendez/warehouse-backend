package com.rammendez.warehouse.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WarehouseScopeRepository extends JpaRepository<WarehouseScope, WarehouseScope.Id> {
    List<WarehouseScope> findByUserIdOrderByWarehouseId(long userId);
    void deleteByUserId(long userId);
}
