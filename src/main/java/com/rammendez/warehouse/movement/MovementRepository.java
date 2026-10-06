package com.rammendez.warehouse.movement;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class MovementRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private static final RowMapper<MovementDtos.Response> HEADER =
            (r, n) ->
                    new MovementDtos.Response(
                            r.getObject("id", UUID.class),
                            r.getString("movement_number"),
                            MovementType.valueOf(r.getString("movement_type")),
                            MovementStatus.valueOf(r.getString("status")),
                            Sql.nullableLong(r, "source_warehouse_id"),
                            Sql.nullableLong(r, "target_warehouse_id"),
                            r.getString("reason"),
                            r.getString("external_reference"),
                            r.getLong("created_by"),
                            Sql.nullableLong(r, "posted_by"),
                            Sql.readNullableInstant(r, "created_at"),
                            Sql.readNullableInstant(r, "posted_at"),
                            r.getObject("compensates_movement_id", UUID.class),
                            r.getObject("transfer_group_id", UUID.class),
                            r.getObject("purchase_order_id", UUID.class),
                            List.of());

    public MovementRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public MovementDtos.Response getStockMovementWithLines(UUID id) {
        return createStockMovementResponseWithLines(
                Sql.firstRowOrThrowNotFound(
                        jdbc.query("select * from stock_movement where id=?", HEADER, id),
                        "Movement"),
                findStockMovementLines(id));
    }

    public void lockStockMovement(UUID id) {
        Sql.firstRowOrThrowNotFound(
                jdbc.query(
                        "select id from stock_movement where id=? for update",
                        (r, n) -> r.getObject(1, UUID.class),
                        id),
                "Movement");
    }

    public List<UUID> findTransferMovementIdsInLockOrder(UUID group) {
        return jdbc.queryForList(
                "select id from stock_movement where transfer_group_id=? order by id",
                UUID.class,
                group);
    }

    public List<MovementDtos.Line> findStockMovementLines(UUID id) {
        return jdbc.query(
                "select * from stock_movement_line where movement_id=? order by id",
                (r, n) -> mapStockMovementLineRow(r),
                id);
    }

    private static MovementDtos.Line mapStockMovementLineRow(java.sql.ResultSet r) throws java.sql.SQLException {
        return new MovementDtos.Line(
                r.getLong("id"),
                r.getLong("product_id"),
                Sql.nullableLong(r, "source_location_id"),
                Sql.nullableLong(r, "target_location_id"),
                r.getBigDecimal("quantity"),
                r.getBigDecimal("unit_cost"));
    }

    public void insertDraftStockMovement(
            UUID id,
            MovementType type,
            Long source,
            Long target,
            String reason,
            String reference,
            long actor,
            UUID compensation,
            UUID group,
            UUID purchase) {
        jdbc.update(
                "insert into"
                    + " stock_movement(id,movement_number,movement_type,status,source_warehouse_id,target_warehouse_id,reason,external_reference,created_by,compensates_movement_id,transfer_group_id,purchase_order_id)"
                    + " values (?,?,?,'DRAFT',?,?,?,?,?,?,?,?)",
                id,
                "MOV-" + id,
                type.name(),
                source,
                target,
                reason,
                reference,
                actor,
                compensation,
                group,
                purchase);
    }

    public void insertStockMovementLine(UUID id, MovementDtos.Line line) {
        jdbc.update(
                "insert into"
                    + " stock_movement_line(movement_id,product_id,source_location_id,target_location_id,quantity,unit_cost)"
                    + " values (?,?,?,?,?,?)",
                id,
                line.productId(),
                line.sourceLocationId(),
                line.targetLocationId(),
                line.quantity(),
                line.unitCost());
    }

    public void markStockMovementPostedWithActor(UUID id, long actor) {
        jdbc.update(
                "update stock_movement set"
                    + " status='POSTED',posted_by=?,posted_at=now(),version=version+1 where id=?",
                actor,
                id);
    }

    public void requireActiveProduct(long id) {
        var active =
                Sql.firstRowOrThrowNotFound(
                        jdbc.query(
                                "select active from product where id=?",
                                (r, n) -> r.getBoolean(1),
                                id),
                        "Product");
        if (!active) {
            throw BusinessException.conflict("Product is inactive");
        }
    }

    public PageResponse<MovementDtos.Response> listStockMovementsWithinUserScope(
            long userId, Long warehouseId, Long productId, MovementType type, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        String where =
                " from stock_movement m where (m.source_warehouse_id is null or exists(select 1"
                    + " from security_user_warehouse_scope s where s.user_id=:user and"
                    + " s.warehouse_id=m.source_warehouse_id)) and (m.target_warehouse_id is null"
                    + " or exists(select 1 from security_user_warehouse_scope s where"
                    + " s.user_id=:user and s.warehouse_id=m.target_warehouse_id))";
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        if (warehouseId != null) {
            where += " and (m.source_warehouse_id=:warehouse or m.target_warehouse_id=:warehouse)";
            params.put("warehouse", warehouseId);
        }
        if (productId != null) {
            where +=
                    " and exists(select 1 from stock_movement_line l where l.movement_id=m.id and"
                            + " l.product_id=:product)";
            params.put("product", productId);
        }
        if (type != null) {
            where += " and m.movement_type=:type";
            params.put("type", type.name());
        }
        long count = named.queryForObject("select count(*)" + where, params, Long.class);
        params.put("size", size);
        params.put("offset", offset);
        var headers =
                named.query(
                        "select m.*"
                                + where
                                + " order by m.created_at desc,m.id limit :size offset :offset",
                        params,
                        HEADER);
        if (headers.isEmpty()) {
            return new PageResponse<>(headers, count, page, size);
        }
        var ids = headers.stream().map(MovementDtos.Response::id).toList();
        var grouped = new HashMap<UUID, List<MovementDtos.Line>>();
        named.query(
                "select * from stock_movement_line where movement_id in (:ids) order by id",
                Map.of("ids", ids),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r ->
                                grouped.computeIfAbsent(
                                                r.getObject("movement_id", UUID.class),
                                                ignored -> new ArrayList<>())
                                        .add(mapStockMovementLineRow(r)));
        return new PageResponse<>(
                headers.stream()
                        .map(h -> createStockMovementResponseWithLines(h, grouped.getOrDefault(h.id(), List.of())))
                        .toList(),
                count,
                page,
                size);
    }

    private MovementDtos.Response createStockMovementResponseWithLines(
            MovementDtos.Response h, List<MovementDtos.Line> lines) {
        return new MovementDtos.Response(
                h.id(),
                h.movementNumber(),
                h.type(),
                h.status(),
                h.sourceWarehouseId(),
                h.targetWarehouseId(),
                h.reason(),
                h.reference(),
                h.createdBy(),
                h.postedBy(),
                h.createdAt(),
                h.postedAt(),
                h.compensatesMovementId(),
                h.transferGroupId(),
                h.purchaseOrderId(),
                lines);
    }
}
