package com.rammendez.warehouse.warehouse;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<WarehouseDtos.Response> ROW =
            (r, n) ->
                    new WarehouseDtos.Response(
                            r.getLong("id"),
                            r.getString("code"),
                            r.getString("name"),
                            r.getBoolean("active"));
    private static final RowMapper<WarehouseDtos.Location> LOCATION =
            (r, n) ->
                    new WarehouseDtos.Location(
                            r.getLong("id"),
                            r.getLong("warehouse_id"),
                            r.getString("code"),
                            r.getString("description"),
                            r.getBoolean("active"));

    public WarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WarehouseDtos.Response getWarehouse(long id) {
        return Sql.firstRowOrThrowNotFound(jdbc.query("select * from warehouse where id=?", ROW, id), "Warehouse");
    }

    public PageResponse<WarehouseDtos.Response> listWarehousesWithinUserScope(long userId, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        String from =
                " from warehouse w join security_user_warehouse_scope s on s.warehouse_id=w.id"
                        + " where s.user_id=?";
        return new PageResponse<>(
                jdbc.query(
                        "select w.*" + from + " order by w.id limit ? offset ?",
                        ROW,
                        userId,
                        size,
                        offset),
                jdbc.queryForObject("select count(*)" + from, Long.class, userId),
                page,
                size);
    }

    public long insert(WarehouseDtos.Input input) {
        return jdbc.queryForObject(
                "insert into warehouse(code,name,active) values (?,?,?) returning id",
                Long.class,
                input.code().trim(),
                input.name().trim(),
                input.active());
    }

    public void update(long id, WarehouseDtos.Input input) {
        jdbc.update(
                "update warehouse set code=?,name=?,active=? where id=?",
                input.code().trim(),
                input.name().trim(),
                input.active(),
                id);
    }

    public void grantCreatorWarehouseManagerScope(long userId, long warehouseId) {
        jdbc.update(
                "insert into security_user_warehouse_scope(user_id,warehouse_id,scope_role) values"
                        + " (?,?,'MANAGER')",
                userId,
                warehouseId);
    }

    public long createLocation(long warehouseId, WarehouseDtos.LocationInput input) {
        return jdbc.queryForObject(
                "insert into warehouse_location(warehouse_id,code,description,active) values"
                        + " (?,?,?,?) returning id",
                Long.class,
                warehouseId,
                input.code().trim(),
                input.description(),
                input.active());
    }

    public void updateLocation(long id, WarehouseDtos.LocationInput input) {
        jdbc.update(
                "update warehouse_location set code=?,description=?,active=? where id=?",
                input.code().trim(),
                input.description(),
                input.active(),
                id);
    }

    public WarehouseDtos.Location getWarehouseLocation(long warehouseId, long id) {
        return Sql.firstRowOrThrowNotFound(
                jdbc.query(
                        "select * from warehouse_location where warehouse_id=? and id=?",
                        LOCATION,
                        warehouseId,
                        id),
                "Location");
    }

    public PageResponse<WarehouseDtos.Location> listWarehouseLocations(long warehouseId, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        return new PageResponse<>(
                jdbc.query(
                        "select * from warehouse_location where warehouse_id=? order by id limit ?"
                                + " offset ?",
                        LOCATION,
                        warehouseId,
                        size,
                        page),
                jdbc.queryForObject(
                        "select count(*) from warehouse_location where warehouse_id=?",
                        Long.class,
                        warehouseId),
                page,
                size);
    }

    public WarehouseDtos.Location getActiveDefaultWarehouseLocation(long warehouseId) {
        return Sql.firstRowOrThrowNotFound(
                jdbc.query(
                        "select * from warehouse_location where warehouse_id=? and code='DEFAULT'"
                                + " and active=true",
                        LOCATION,
                        warehouseId),
                "Default location");
    }
}
