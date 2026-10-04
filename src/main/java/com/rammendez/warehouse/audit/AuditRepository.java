package com.rammendez.warehouse.audit;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Repository
public class AuditRepository {
    public record MovementAudit(
            long id,
            UUID movementId,
            String operation,
            Long actorUserId,
            String dbUser,
            JsonNode oldRow,
            JsonNode newRow,
            Instant changedAt) {}

    public record Event(
            long id,
            Long actorUserId,
            String eventType,
            String entityType,
            String entityId,
            JsonNode eventData,
            Instant occurredAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PageResponse<MovementAudit> movements(long userId, UUID movementId, int page, int size) {
        int offset = PageResponse.offset(page, size);
        String where = " where 1=1";
        var args = new ArrayList<Object>();
        // Historical and current snapshots each require access to every referenced warehouse.
        for (String snapshot : List.of("old_row", "new_row")) {
            for (String column : List.of("source_warehouse_id", "target_warehouse_id")) {
                String warehouse = "(" + snapshot + "->>'" + column + "')";
                where +=
                        " and ("
                                + warehouse
                                + " is null or exists(select 1 from security_user_warehouse_scope s"
                                + " where s.user_id=? and s.warehouse_id="
                                + warehouse
                                + "::bigint))";
                args.add(userId);
            }
        }
        if (movementId != null) {
            where += " and movement_id=?";
            args.add(movementId);
        }
        long count =
                jdbc.queryForObject(
                        "select count(*) from stock_movement_audit" + where,
                        Long.class,
                        args.toArray());
        args.add(size);
        args.add(offset);
        var rows =
                jdbc.query(
                        "select * from stock_movement_audit"
                                + where
                                + " order by changed_at desc,id desc limit ? offset ?",
                        (r, n) ->
                                new MovementAudit(
                                        r.getLong("id"),
                                        r.getObject("movement_id", UUID.class),
                                        r.getString("operation"),
                                        Sql.nullableLong(r, "actor_user_id"),
                                        r.getString("db_user"),
                                        json(r.getString("old_row")),
                                        json(r.getString("new_row")),
                                        Sql.instant(r, "changed_at")),
                        args.toArray());
        return new PageResponse<>(rows, count, page, size);
    }

    public PageResponse<Event> events(
            long userId,
            boolean admin,
            boolean contact,
            String entityType,
            String entityId,
            int page,
            int size) {
        int offset = PageResponse.offset(page, size);
        String where =
                " where (warehouse_id is null or exists(select 1 from security_user_warehouse_scope"
                    + " s where s.user_id=? and s.warehouse_id=audit_event.warehouse_id)) and"
                    + " (related_warehouse_id is null or exists(select 1 from"
                    + " security_user_warehouse_scope s where s.user_id=? and"
                    + " s.warehouse_id=audit_event.related_warehouse_id)) and"
                    + " (entity_type<>'security_user' or ?) and (entity_type<>'contact_message' or"
                    + " ?)";
        var args = new ArrayList<Object>();
        args.add(userId);
        args.add(userId);
        args.add(admin);
        args.add(contact);
        if (entityType != null) {
            where += " and entity_type=?";
            args.add(entityType);
        }
        if (entityId != null) {
            where += " and entity_id=?";
            args.add(entityId);
        }
        long count =
                jdbc.queryForObject(
                        "select count(*) from audit_event" + where, Long.class, args.toArray());
        args.add(size);
        args.add(offset);
        var rows =
                jdbc.query(
                        "select * from audit_event"
                                + where
                                + " order by occurred_at desc,id desc limit ? offset ?",
                        (r, n) ->
                                new Event(
                                        r.getLong("id"),
                                        Sql.nullableLong(r, "actor_user_id"),
                                        r.getString("event_type"),
                                        r.getString("entity_type"),
                                        r.getString("entity_id"),
                                        json(r.getString("event_data")),
                                        Sql.instant(r, "occurred_at")),
                        args.toArray());
        return new PageResponse<>(rows, count, page, size);
    }

    private JsonNode json(String value) {
        return value == null ? null : mapper.readTree(value);
    }
}
