package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.PageResponse;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditReadService service;

    public AuditController(AuditReadService service) {
        this.service = service;
    }

    @GetMapping("/stock-movements")
    public PageResponse<AuditRepository.MovementAudit> listMovementAuditHistoryWithinUserScope(
            @RequestParam(required = false) UUID movementId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listMovementAuditHistoryWithinUserScope(movementId, page, size);
    }

    @GetMapping("/movements/{movementId}")
    public PageResponse<AuditRepository.MovementAudit> listAuditHistoryForMovement(
            @PathVariable UUID movementId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listMovementAuditHistoryWithinUserScope(movementId, page, size);
    }

    @GetMapping("/events")
    public PageResponse<AuditRepository.Event> listAuditEventsWithinUserPermissionsAndScope(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listAuditEventsWithinUserPermissionsAndScope(entityType, entityId, page, size);
    }
}
