package com.rammendez.warehouse.product;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;

@Repository
public class ProductQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<ProductDtos.SupplierLink> listProductSupplierLinks(long productId, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var params = java.util.Map.of("product", productId, "size", size, "offset", offset);
        String from =
                " from product_supplier ps join supplier s on s.id=ps.supplier_id where"
                        + " ps.product_id=:product";
        long count = jdbc.queryForObject("select count(*)" + from, params, Long.class);
        var content =
                jdbc.query(
                        "select ps.*,s.code,s.name"
                                + from
                                + " order by s.id limit :size offset :offset",
                        params,
                        (row, index) ->
                                new ProductDtos.SupplierLink(
                                        row.getLong("supplier_id"),
                                        row.getString("code"),
                                        row.getString("name"),
                                        row.getString("supplier_product_code"),
                                        row.getBigDecimal("unit_cost"),
                                        row.getBoolean("preferred")));
        return new PageResponse<>(content, count, page, size);
    }

    public PageResponse<ProductDtos.Response> listFilteredAndSortedProducts(
            String search,
            String sku,
            Long categoryId,
            Long supplierId,
            Boolean active,
            int page,
            int size,
            String sort) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        String order =
                switch (sort) {
                    case "name" -> "p.name,p.id";
                    case "sku" -> "p.sku,p.id";
                    case "createdAt" -> "p.created_at desc,p.id";
                    case "id" -> "p.id";
                    default ->
                            throw BusinessException.invalid(
                                    "sort must be id, name, sku, or createdAt");
                };
        var params = new HashMap<String, Object>();
        var where = new StringBuilder(" from product p where 1=1");
        if (search != null && !search.isBlank()) {
            where.append(
                    " and (lower(p.name) like :search escape '\\' or lower(p.sku) like :search"
                            + " escape '\\')");
            params.put(
                    "search",
                    Sql.createEscapedContainsLikePattern(search.toLowerCase(java.util.Locale.ROOT)));
        }
        if (sku != null) {
            where.append(" and p.sku=:sku");
            params.put("sku", sku);
        }
        if (categoryId != null) {
            where.append(" and p.category_id=:category");
            params.put("category", categoryId);
        }
        if (supplierId != null) {
            where.append(
                    " and exists(select 1 from product_supplier ps where ps.product_id=p.id and"
                            + " ps.supplier_id=:supplier)");
            params.put("supplier", supplierId);
        }
        if (active != null) {
            where.append(" and p.active=:active");
            params.put("active", active);
        }
        long count = jdbc.queryForObject("select count(*)" + where, params, Long.class);
        params.put("size", size);
        params.put("offset", offset);
        var content =
                jdbc.query(
                        "select p.*" + where + " order by " + order + " limit :size offset :offset",
                        params,
                        (r, n) ->
                                new ProductDtos.Response(
                                        r.getLong("id"),
                                        r.getString("sku"),
                                        r.getString("barcode"),
                                        r.getString("name"),
                                        r.getString("description"),
                                        Sql.nullableLong(r, "category_id"),
                                        r.getString("unit"),
                                        r.getBigDecimal("minimum_stock"),
                                        r.getBoolean("active"),
                                        r.getLong("version"),
                                        Sql.readNullableInstant(r, "created_at"),
                                        Sql.readNullableInstant(r, "updated_at")));
        return new PageResponse<>(content, count, page, size);
    }

    public void upsertProductSupplierCostIfSupplierProvided(long productId, Long supplierId, java.math.BigDecimal cost) {
        if (supplierId != null) {
            jdbc.update(
                    "insert into product_supplier(product_id,supplier_id,unit_cost,preferred)"
                            + " values (:product,:supplier,:cost,true) on"
                            + " conflict(product_id,supplier_id) do update set"
                            + " unit_cost=excluded.unit_cost",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                            .addValue("product", productId)
                            .addValue("supplier", supplierId)
                            .addValue("cost", cost));
        }
    }
}
