package com.rammendez.warehouse.warehouse;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.security.AccessControl;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WarehouseService {
    private final WarehouseRepository repository;
    private final AccessControl access;
    private final AuditService audit;

    public WarehouseService(
            WarehouseRepository repository, AccessControl access, AuditService audit) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public PageResponse<WarehouseDtos.Response> list(int page, int size) {
        return repository.list(access.userId(), page, size);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public WarehouseDtos.Response get(long id) {
        access.warehouse(id, false);
        return repository.get(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Response create(WarehouseDtos.Input input) {
        long id = repository.insert(input);
        repository.createLocation(
                id, new WarehouseDtos.LocationInput("DEFAULT", "Default stock location", true));
        repository.grantCreator(access.userId(), id);
        audit.event("WAREHOUSE_CREATED", "warehouse", id);
        return repository.get(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Response update(long id, WarehouseDtos.Input input) {
        access.warehouse(id, true);
        repository.get(id);
        repository.update(id, input);
        audit.event("WAREHOUSE_UPDATED", "warehouse", id);
        return repository.get(id);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public PageResponse<WarehouseDtos.Location> locations(long warehouseId, int page, int size) {
        access.warehouse(warehouseId, false);
        repository.get(warehouseId);
        return repository.locations(warehouseId, page, size);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public WarehouseDtos.Location location(long warehouseId, long id) {
        access.warehouse(warehouseId, false);
        return repository.location(warehouseId, id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Location createLocation(
            long warehouseId, WarehouseDtos.LocationInput input) {
        access.warehouse(warehouseId, true);
        repository.get(warehouseId);
        long id = repository.createLocation(warehouseId, input);
        audit.event("LOCATION_CREATED", "warehouse_location", id);
        return repository.location(warehouseId, id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Location updateLocation(
            long warehouseId, long id, WarehouseDtos.LocationInput input) {
        access.warehouse(warehouseId, true);
        repository.location(warehouseId, id);
        repository.updateLocation(id, input);
        audit.event("LOCATION_UPDATED", "warehouse_location", id);
        return repository.location(warehouseId, id);
    }
}
