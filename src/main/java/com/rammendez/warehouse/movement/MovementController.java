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
    public MovementDtos.Response receiveStockAndPostReceiptMovement(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.receiveStockAndPostReceiptMovement(input);
    }

    @PostMapping("/movements/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response issueAvailableStockAndPostIssueMovement(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.issueAvailableStockAndPostIssueMovement(input);
    }

    @PostMapping("/movements/return")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response returnStockAndPostReturnMovement(@Valid @RequestBody MovementDtos.StockInput input) {
        return service.returnStockAndPostReturnMovement(input);
    }

    @PostMapping("/movements/adjustment")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Response applySignedStockAdjustmentAndPostMovement(@Valid @RequestBody MovementDtos.Adjustment input) {
        return service.applySignedStockAdjustmentAndPostMovement(input);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Result transferStockAndPostLinkedMovementsAtomically(@Valid @RequestBody MovementDtos.Transfer input) {
        return service.transferStockAndPostLinkedMovementsAtomically(input);
    }

    @PostMapping("/movements/{id}/compensate")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementDtos.Result reversePostedMovementOrTransferWithCompensatingMovements(
            @PathVariable UUID id, @Valid @RequestBody MovementDtos.Compensation input) {
        return service.reversePostedMovementOrTransferWithCompensatingMovements(id, input.reason());
    }

    @GetMapping("/movements/{id}")
    public MovementDtos.Response getStockMovementWithLines(@PathVariable UUID id) {
        return service.getStockMovementWithLines(id);
    }

    @GetMapping("/movements")
    public PageResponse<MovementDtos.Response> listStockMovementsWithinUserScope(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listStockMovementsWithinUserScope(warehouseId, productId, type, page, size);
    }
}
