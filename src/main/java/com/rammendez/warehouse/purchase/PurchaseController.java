package com.rammendez.warehouse.purchase;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseController {
    private final PurchaseService service;

    public PurchaseController(PurchaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseDtos.Response create(@Valid @RequestBody PurchaseDtos.Input input) {
        return service.create(input);
    }

    @GetMapping
    public PageResponse<PurchaseDtos.Summary> list(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) PurchaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(warehouseId, status, page, size);
    }

    @GetMapping("/{id}")
    public PurchaseDtos.Response get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/lines")
    public PurchaseDtos.Response addLine(
            @PathVariable UUID id, @Valid @RequestBody PurchaseDtos.LineInput input) {
        return service.addLine(id, input);
    }

    @PostMapping("/{id}/submit")
    public PurchaseDtos.Response submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/approve")
    public PurchaseDtos.Response approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/receive")
    public PurchaseDtos.Response receive(@PathVariable UUID id) {
        return service.receive(id);
    }

    @PostMapping("/{id}/cancel")
    public PurchaseDtos.Response cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
