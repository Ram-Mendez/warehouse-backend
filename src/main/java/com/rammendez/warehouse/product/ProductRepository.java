package com.rammendez.warehouse.product;

import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query("""
            select p from Product p
            where (:search is null or lower(p.name) like :search escape '\\'
                   or lower(p.sku) like :search escape '\\')
              and (:sku is null or p.sku = :sku)
              and (:categoryId is null or p.categoryId = :categoryId)
              and (:supplierId is null or exists (
                  select 1 from ProductSupplier ps
                  where ps.id.productId = p.id and ps.id.supplierId = :supplierId))
              and (:active is null or p.active = :active)
            """)
    Page<Product> findFiltered(String search, String sku, Long categoryId, Long supplierId,
            Boolean active, Pageable pageable);

    @Query(value = """
            select ps from ProductSupplier ps join fetch ps.supplier
            where ps.id.productId = :productId
            order by ps.id.supplierId
            """, countQuery = "select count(ps) from ProductSupplier ps where ps.id.productId = :productId")
    Page<ProductSupplier> findSupplierLinks(long productId, Pageable pageable);

    // PostgreSQL upsert preserves the existing atomic insert-or-update contract for a link.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into product_supplier(product_id, supplier_id, unit_cost, preferred)
            values (:productId, :supplierId, :cost, true)
            on conflict(product_id, supplier_id) do update set unit_cost = excluded.unit_cost
            """, nativeQuery = true)
    void upsertSupplierCost(long productId, long supplierId, BigDecimal cost);
}
