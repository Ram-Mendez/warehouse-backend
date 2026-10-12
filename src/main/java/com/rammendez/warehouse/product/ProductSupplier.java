package com.rammendez.warehouse.product;

import com.rammendez.warehouse.supplier.Supplier;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "product_supplier")
public class ProductSupplier {
    @EmbeddedId
    Id id;

    @MapsId("productId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    Product product;

    @MapsId("supplierId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    Supplier supplier;

    @Column(name = "supplier_product_code", length = 100)
    String supplierProductCode;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    BigDecimal unitCost;

    @Column(nullable = false)
    boolean preferred;

    protected ProductSupplier() {}

    ProductDtos.SupplierLink toResponse() {
        return new ProductDtos.SupplierLink(supplier.getId(), supplier.getCode(), supplier.getName(),
                supplierProductCode, unitCost, preferred);
    }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "product_id")
        Long productId;

        @Column(name = "supplier_id")
        Long supplierId;

        protected Id() {}

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Id that
                    && Objects.equals(productId, that.productId)
                    && Objects.equals(supplierId, that.supplierId);
        }

        @Override
        public int hashCode() { return Objects.hash(productId, supplierId); }
    }
}
