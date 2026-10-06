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
    public PageResponse<WarehouseDtos.Response> listWarehousesWithinUserScope(int page, int size) {
        return repository.listWarehousesWithinUserScope(access.getAuthenticatedUserId(), page, size);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public WarehouseDtos.Response getWarehouse(long id) {
        access.requireWarehouseScope(id, false);
        return repository.getWarehouse(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Response createWarehouseWithDefaultLocationAndCreatorScope(WarehouseDtos.Input input) {
        long id = repository.insert(input);
        repository.createLocation(
                id, new WarehouseDtos.LocationInput("DEFAULT", "Default stock location", true));
        repository.grantCreatorWarehouseManagerScope(access.getAuthenticatedUserId(), id);
        audit.recordAuditEventWithActorAndWarehouseReferences("WAREHOUSE_CREATED", "warehouse", id);
        return repository.getWarehouse(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Response update(long id, WarehouseDtos.Input input) {
        access.requireWarehouseScope(id, true);
        repository.getWarehouse(id);
        repository.update(id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("WAREHOUSE_UPDATED", "warehouse", id);
        return repository.getWarehouse(id);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public PageResponse<WarehouseDtos.Location> listWarehouseLocations(long warehouseId, int page, int size) {
        access.requireWarehouseScope(warehouseId, false);
        repository.getWarehouse(warehouseId);
        return repository.listWarehouseLocations(warehouseId, page, size);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public WarehouseDtos.Location getWarehouseLocation(long warehouseId, long id) {
        access.requireWarehouseScope(warehouseId, false);
        return repository.getWarehouseLocation(warehouseId, id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Location createLocation(
            long warehouseId, WarehouseDtos.LocationInput input) {
        access.requireWarehouseScope(warehouseId, true);
        repository.getWarehouse(warehouseId);
        long id = repository.createLocation(warehouseId, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("LOCATION_CREATED", "warehouse_location", id);
        return repository.getWarehouseLocation(warehouseId, id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Location updateLocation(
            long warehouseId, long id, WarehouseDtos.LocationInput input) {
        access.requireWarehouseScope(warehouseId, true);
        repository.getWarehouseLocation(warehouseId, id);
        repository.updateLocation(id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("LOCATION_UPDATED", "warehouse_location", id);
        return repository.getWarehouseLocation(warehouseId, id);
    }
}
