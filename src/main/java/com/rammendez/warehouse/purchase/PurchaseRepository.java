package com.rammendez.warehouse.purchase;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class PurchaseRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<PurchaseDtos.Response> HEADER =
            (r, n) ->
                    new PurchaseDtos.Response(
                            r.getObject("id", UUID.class),
                            r.getString("order_number"),
                            r.getLong("supplier_id"),
                            r.getLong("target_warehouse_id"),
                            PurchaseStatus.valueOf(r.getString("status")),
                            r.getLong("created_by"),
                            r.getLong("version"),
                            Sql.readNullableInstant(r, "created_at"),
                            List.of());

    public PurchaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PurchaseDtos.Response getPurchaseOrderWithLinesAndOptionalLock(UUID id, boolean lock) {
        var header =
                Sql.firstRowOrThrowNotFound(
                        jdbc.query(
                                "select * from purchase_order where id=?"
                                        + (lock ? " for update" : ""),
                                HEADER,
                                id),
                        "Purchase order");
        var lines =
                jdbc.query(
                        "select * from purchase_order_line where purchase_order_id=? order by id",
                        (r, n) ->
                                new PurchaseDtos.Line(
                                        r.getLong("id"),
                                        r.getLong("product_id"),
                                        r.getBigDecimal("ordered_quantity"),
                                        r.getBigDecimal("received_quantity"),
                                        r.getBigDecimal("unit_cost")),
                        id);
        return new PurchaseDtos.Response(
                header.id(),
                header.orderNumber(),
                header.supplierId(),
                header.warehouseId(),
                header.status(),
                header.createdBy(),
                header.version(),
                header.createdAt(),
                lines);
    }

    public UUID insertDraftPurchaseOrder(PurchaseDtos.Input input, long actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into"
                        + " purchase_order(id,order_number,supplier_id,target_warehouse_id,status,created_by)"
                        + " values (?,?,?,?,'DRAFT',?)",
                id,
                input.orderNumber().trim(),
                input.supplierId(),
                input.warehouseId(),
                actor);
        return id;
    }

    public void insertPurchaseOrderLine(UUID id, PurchaseDtos.LineInput input) {
        jdbc.update(
                "insert into"
                        + " purchase_order_line(purchase_order_id,product_id,ordered_quantity,unit_cost)"
                        + " values (?,?,?,?)",
                id,
                input.productId(),
                input.quantity(),
                input.unitCost());
    }

    public void updatePurchaseOrderStatusAndAdvanceVersion(UUID id, PurchaseStatus status) {
        jdbc.update(
                "update purchase_order set status=?,version=version+1 where id=?",
                status.name(),
                id);
    }

    public void markPurchaseOrderApprovedWithActor(UUID id, long actor) {
        jdbc.update(
                "update purchase_order set"
                        + " status='APPROVED',approved_by=?,approved_at=now(),version=version+1 where"
                        + " id=?",
                actor,
                id);
    }

    public void markAllPurchaseLinesAndOrderReceived(UUID id) {
        jdbc.update(
                "update purchase_order_line set received_quantity=ordered_quantity where"
                        + " purchase_order_id=?",
                id);
        updatePurchaseOrderStatusAndAdvanceVersion(id, PurchaseStatus.RECEIVED);
    }

    public PageResponse<PurchaseDtos.Summary> listPurchaseOrdersWithinUserScope(
            long actor, Long warehouseId, PurchaseStatus status, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        String where =
                " from purchase_order p join security_user_warehouse_scope s on"
                        + " s.warehouse_id=p.target_warehouse_id where s.user_id=?";
        var args = new ArrayList<Object>();
        args.add(actor);
        if (warehouseId != null) {
            where += " and p.target_warehouse_id=?";
            args.add(warehouseId);
        }
        if (status != null) {
            where += " and p.status=?";
            args.add(status.name());
        }
        long count = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
        args.add(size);
        args.add(offset);
        var headers =
                jdbc.query(
                        "select p.*" + where + " order by p.created_at desc,p.id limit ? offset ?",
                        HEADER,
                        args.toArray());
        var summaries =
                headers.stream()
                        .map(
                                header ->
                                        new PurchaseDtos.Summary(
                                                header.id(),
                                                header.orderNumber(),
                                                header.supplierId(),
                                                header.warehouseId(),
                                                header.status(),
                                                header.createdBy(),
                                                header.version(),
                                                header.createdAt()))
                        .toList();
        return new PageResponse<>(summaries, count, page, size);
    }
}
