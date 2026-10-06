package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.security.AccessControl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final AccessControl access;

    public AuditService(JdbcTemplate jdbc, AccessControl access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public void setTransactionLocalAuthenticatedAuditActor() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit actor requires transaction");
        }
        jdbc.queryForObject(
                "select set_config('app.current_user_id',?,true)",
                String.class,
                Long.toString(access.getAuthenticatedUserId()));
    }

    public void recordAuditEventWithActorAndWarehouseReferences(String type, String entity, Object id) {
        Long warehouse = null;
        Long related = null;
        switch (entity) {
            case "warehouse" -> warehouse = Long.valueOf(id.toString());
            case "warehouse_location" ->
                    warehouse =
                            jdbc.queryForObject(
                                    "select warehouse_id from warehouse_location where id=?",
                                    Long.class,
                                    id);
            case "purchase_order" ->
                    warehouse =
                            jdbc.queryForObject(
                                    "select target_warehouse_id from purchase_order where id=?",
                                    Long.class,
                                    id);
            case "stock_movement", "transfer" -> {
                String field = entity.equals("transfer") ? "transfer_group_id" : "id";
                var rows =
                        jdbc.query(
                                "select source_warehouse_id,target_warehouse_id from stock_movement"
                                        + " where "
                                        + field
                                        + "=? limit 1",
                                (r, n) ->
                                        new Long[] {
                                            r.getObject(1, Long.class), r.getObject(2, Long.class)
                                        },
                                id);
                if (!rows.isEmpty()) {
                    warehouse = rows.getFirst()[0];
                    related = rows.getFirst()[1];
                }
            }
            default -> {}
        }
        jdbc.update(
                "insert into"
                    + " audit_event(actor_user_id,event_type,entity_type,entity_id,warehouse_id,related_warehouse_id)"
                    + " values (?,?,?,?,?,?)",
                access.getAuthenticatedUserId(),
                type,
                entity,
                id.toString(),
                warehouse,
                related);
    }
}
