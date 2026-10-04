package com.rammendez.warehouse.warehouse;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {
    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<WarehouseDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public WarehouseDtos.Response get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WarehouseDtos.Response create(@Valid @RequestBody WarehouseDtos.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public WarehouseDtos.Response update(
            @PathVariable long id, @Valid @RequestBody WarehouseDtos.Input input) {
        return service.update(id, input);
    }

    @GetMapping("/{warehouseId}/locations")
    public PageResponse<WarehouseDtos.Location> locations(
            @PathVariable long warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.locations(warehouseId, page, size);
    }

    @PostMapping("/{warehouseId}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public WarehouseDtos.Location createLocation(
            @PathVariable long warehouseId, @Valid @RequestBody WarehouseDtos.LocationInput input) {
        return service.createLocation(warehouseId, input);
    }

    @GetMapping("/{warehouseId}/locations/{id}")
    public WarehouseDtos.Location location(@PathVariable long warehouseId, @PathVariable long id) {
        return service.location(warehouseId, id);
    }

    @PutMapping("/{warehouseId}/locations/{id}")
    public WarehouseDtos.Location updateLocation(
            @PathVariable long warehouseId,
            @PathVariable long id,
            @Valid @RequestBody WarehouseDtos.LocationInput input) {
        return service.updateLocation(warehouseId, id, input);
    }
}
