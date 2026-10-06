package com.rammendez.warehouse.category;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<CategoryDtos.Response> ROW =
            (r, n) ->
                    new CategoryDtos.Response(
                            r.getLong("id"),
                            r.getString("code"),
                            r.getString("name"),
                            Sql.nullableLong(r, "parent_id"),
                            r.getBoolean("active"));

    public CategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockHierarchy() {
        jdbc.queryForObject("select pg_advisory_xact_lock(4815162342)", Object.class);
    }

    public CategoryDtos.Response getCategory(long id) {
        return Sql.firstRowOrThrowNotFound(jdbc.query("select * from category where id=?", ROW, id), "Category");
    }

    public PageResponse<CategoryDtos.Response> list(int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        return new PageResponse<>(
                jdbc.query(
                        "select * from category order by id limit ? offset ?", ROW, size, offset),
                jdbc.queryForObject("select count(*) from category", Long.class),
                page,
                size);
    }

    public long insert(CategoryDtos.Input input) {
        return jdbc.queryForObject(
                "insert into category(code,name,parent_id,active) values (?,?,?,?) returning id",
                Long.class,
                input.code().trim(),
                input.name().trim(),
                input.parentId(),
                input.active());
    }

    public void update(long id, CategoryDtos.Input input) {
        jdbc.update(
                "update category set code=?,name=?,parent_id=?,active=? where id=?",
                input.code().trim(),
                input.name().trim(),
                input.parentId(),
                input.active(),
                id);
    }
}
