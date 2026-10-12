package com.rammendez.warehouse.warehouse;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.security.AccessControl;
import com.rammendez.warehouse.security.AdminDtos;
import com.rammendez.warehouse.security.WarehouseScope;
import com.rammendez.warehouse.security.WarehouseScopeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WarehouseService {
    private final WarehouseRepository repository;
    private final WarehouseLocationRepository locations;
    private final WarehouseScopeRepository scopes;
    private final AccessControl access;
    private final AuditService audit;

    public WarehouseService(
            WarehouseRepository repository, WarehouseLocationRepository locations,
            WarehouseScopeRepository scopes, AccessControl access, AuditService audit) {
        this.repository = repository;
        this.locations = locations;
        this.scopes = scopes;
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
        var warehouse = new Warehouse();
        applyInput(warehouse, input);
        long id = repository.saveAndFlush(warehouse).id;
        saveLocation(warehouse, new WarehouseDtos.LocationInput("DEFAULT", "Default stock location", true));
        scopes.saveAndFlush(new WarehouseScope(access.getAuthenticatedUserId(), id, AdminDtos.ScopeRole.MANAGER));
        audit.recordAuditEventWithActorAndWarehouseReferences("WAREHOUSE_CREATED", "warehouse", id);
        return repository.getWarehouse(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Response update(long id, WarehouseDtos.Input input) {
        access.requireWarehouseScope(id, true);
        repository.getWarehouse(id);
        var warehouse = repository.findById(id).orElseThrow(() -> BusinessException.missing("Warehouse"));
        applyInput(warehouse, input);
        repository.flush();
        audit.recordAuditEventWithActorAndWarehouseReferences("WAREHOUSE_UPDATED", "warehouse", id);
        return repository.getWarehouse(id);
    }

    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_READ')")
    public PageResponse<WarehouseDtos.Location> listWarehouseLocations(long warehouseId, int page, int size) {
        access.requireWarehouseScope(warehouseId, false);
        repository.getWarehouse(warehouseId);
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = locations.findByWarehouseId(warehouseId, PageRequest.of(page, size, Sort.by("id")));
        return new PageResponse<>(result.map(WarehouseLocation::toResponse).getContent(), result.getTotalElements(), page, size);
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
        long id = saveLocation(repository.getReferenceById(warehouseId), input).id;
        audit.recordAuditEventWithActorAndWarehouseReferences("LOCATION_CREATED", "warehouse_location", id);
        return repository.getWarehouseLocation(warehouseId, id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_WAREHOUSE_MANAGE')")
    public WarehouseDtos.Location updateLocation(
            long warehouseId, long id, WarehouseDtos.LocationInput input) {
        access.requireWarehouseScope(warehouseId, true);
        repository.getWarehouseLocation(warehouseId, id);
        var location = locations.findById(id).orElseThrow(() -> BusinessException.missing("Location"));
        applyLocationInput(location, input);
        locations.flush();
        audit.recordAuditEventWithActorAndWarehouseReferences("LOCATION_UPDATED", "warehouse_location", id);
        return repository.getWarehouseLocation(warehouseId, id);
    }
    private void applyInput(Warehouse warehouse, WarehouseDtos.Input input) {
        warehouse.code = input.code().trim();
        warehouse.name = input.name().trim();
        warehouse.active = input.active();
    }

    private WarehouseLocation saveLocation(Warehouse warehouse, WarehouseDtos.LocationInput input) {
        var location = new WarehouseLocation();
        location.warehouse = warehouse;
        applyLocationInput(location, input);
        return locations.saveAndFlush(location);
    }

    private void applyLocationInput(WarehouseLocation location, WarehouseDtos.LocationInput input) {
        location.code = input.code().trim();
        location.description = input.description();
        location.active = input.active();
    }
}
