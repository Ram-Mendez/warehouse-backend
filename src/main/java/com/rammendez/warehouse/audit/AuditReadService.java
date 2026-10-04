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
    private final com.rammendez.warehouse.security.AccessControl access;
    private final com.rammendez.warehouse.movement.MovementRepository movements;

    public AuditReadService(
            AuditRepository repository,
            com.rammendez.warehouse.security.AccessControl access,
            com.rammendez.warehouse.movement.MovementRepository movements) {
        this.repository = repository;
        this.access = access;
        this.movements = movements;
    }

    public PageResponse<AuditRepository.MovementAudit> movements(UUID id, int page, int size) {
        if (id != null) {
            try {
                var movement = movements.get(id);
                if (movement.sourceWarehouseId() != null) {
                    access.warehouse(movement.sourceWarehouseId(), false);
                }
                if (movement.targetWarehouseId() != null) {
                    access.warehouse(movement.targetWarehouseId(), false);
                }
            } catch (com.rammendez.warehouse.common.BusinessException error) {
                if (error.status() != org.springframework.http.HttpStatus.NOT_FOUND) {
                    throw error;
                }
            }
        }
        return repository.movements(access.userId(), id, page, size);
    }

    public PageResponse<AuditRepository.Event> events(String type, String id, int page, int size) {
        var authorities =
                access.jwt().getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .toList();
        return repository.events(
                access.userId(),
                authorities.contains("PERM_USER_MANAGE"),
                authorities.contains("PERM_CONTACT_READ"),
                type,
                id,
                page,
                size);
    }
}
