package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.PageResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
public class AuditReadService {
    private final AuditRepository repository;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final com.rammendez.warehouse.security.AccessControl access;
    private final com.rammendez.warehouse.movement.MovementRepository movements;

    public AuditReadService(
            AuditRepository repository,
            tools.jackson.databind.ObjectMapper mapper,
            com.rammendez.warehouse.security.AccessControl access,
            com.rammendez.warehouse.movement.MovementRepository movements) {
        this.repository = repository;
        this.mapper = mapper;
        this.access = access;
        this.movements = movements;
    }

    public PageResponse<AuditRepository.MovementAudit> listMovementAuditHistoryWithinUserScope(UUID id, int page, int size) {
        if (id != null) {
            try {
                var movement = movements.getStockMovementWithLines(id);
                if (movement.sourceWarehouseId() != null) {
                    access.requireWarehouseScope(movement.sourceWarehouseId(), false);
                }
                if (movement.targetWarehouseId() != null) {
                    access.requireWarehouseScope(movement.targetWarehouseId(), false);
                }
            } catch (com.rammendez.warehouse.common.BusinessException error) {
                if (error.status() != org.springframework.http.HttpStatus.NOT_FOUND) {
                    throw error;
                }
            }
        }
        return repository.listMovementAuditHistoryWithinUserScope(access.getAuthenticatedUserId(), id, page, size);
    }

    public PageResponse<AuditRepository.Event> listAuditEventsWithinUserPermissionsAndScope(String type, String id, int page, int size) {
        var authorities =
                access.requireJwtAuthentication().getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .toList();
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = repository.findScopedEvents(
                access.getAuthenticatedUserId(),
                authorities.contains("PERM_USER_MANAGE"),
                authorities.contains("PERM_CONTACT_READ"),
                type,
                id,
                org.springframework.data.domain.PageRequest.of(page, size));
        return new PageResponse<>(result.stream().map(e -> new AuditRepository.Event(
                e.id, e.actorUserId, e.eventType, e.entityType, e.entityId,
                mapper.readTree(e.eventData), e.occurredAt)).toList(), result.getTotalElements(), page, size);
    }
}
