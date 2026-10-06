package com.rammendez.warehouse.supplier;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class SupplierRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<SupplierDtos.Response> ROW =
            (r, n) ->
                    new SupplierDtos.Response(
                            r.getLong("id"),
                            r.getString("code"),
                            r.getString("name"),
                            r.getString("email"),
                            r.getString("email"),
                            r.getBoolean("active"));

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SupplierDtos.Response getSupplier(long id) {
        return Sql.firstRowOrThrowNotFound(jdbc.query("select * from supplier where id=?", ROW, id), "Supplier");
    }

    public PageResponse<SupplierDtos.Response> list(int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        return new PageResponse<>(
                jdbc.query(
                        "select * from supplier order by id limit ? offset ?", ROW, size, offset),
                jdbc.queryForObject("select count(*) from supplier", Long.class),
                page,
                size);
    }

    public long insert(SupplierDtos.Input input) {
        return jdbc.queryForObject(
                "insert into supplier(code,name,email,phone,active) values (?,?,?,?,?) returning"
                        + " id",
                Long.class,
                input.code().trim(),
                input.name().trim(),
                input.email(),
                input.phone(),
                input.active());
    }

    public void update(long id, SupplierDtos.Input input) {
        jdbc.update(
                "update supplier set code=?,name=?,email=?,phone=?,active=? where id=?",
                input.code().trim(),
                input.name().trim(),
                input.email(),
                input.phone(),
                input.active(),
                id);
    }
}
