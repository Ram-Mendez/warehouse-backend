package com.rammendez.warehouse.movement;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MovementController {
    private final MovementService service;

    public MovementController(MovementService service) {
        this.service = service;
    }

    @PostMapping("/movements/receipt")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response receipt(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.receipt(input);
    }

    @PostMapping("/movements/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response issue(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.issue(input);
    }

    @PostMapping("/movements/return")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response returned(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.returned(input);
    }

    @PostMapping("/movements/adjustment")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response adjustment(@Valid @RequestBody MovementDtos.Adjustment input) {
        return service.adjustment(input);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Result transfer(@Valid @RequestBody MovementDtos.Transfer input) {
        return service.transfer(input);
    }

    @PostMapping("/movements/{id}/compensate")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Result compensate(
            @PathVariable UUID id, @Valid @RequestBody MovementDtos.Compensation input) {
        return service.compensate(id, input.reason());
    }

    @GetMapping("/movements/{id}")
    public MovementDtos.Response get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/movements")
    public PageResponse<MovementDtos.Response> list(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(warehouseId, productId, type, page, size);
    }
}
