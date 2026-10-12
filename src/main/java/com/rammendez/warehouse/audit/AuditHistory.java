package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.PageResponse;
import java.util.UUID;

public interface AuditHistory {
    PageResponse<AuditRepository.MovementAudit> listMovementAuditHistoryWithinUserScope(
            long userId, UUID movementId, int page, int size);
}
