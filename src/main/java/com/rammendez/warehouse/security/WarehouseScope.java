package com.rammendez.warehouse.security;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "security_user_warehouse_scope")
@IdClass(WarehouseScope.Id.class)
public class WarehouseScope {
    @jakarta.persistence.Id @Column(name = "user_id")
    private Long userId;
    @jakarta.persistence.Id @Column(name = "warehouse_id")
    private Long warehouseId;
    @Enumerated(EnumType.STRING) @Column(name = "scope_role")
    private AdminDtos.ScopeRole scopeRole;

    protected WarehouseScope() {}

    public WarehouseScope(Long userId, Long warehouseId, AdminDtos.ScopeRole scopeRole) {
        this.userId = userId;
        this.warehouseId = warehouseId;
        this.scopeRole = scopeRole;
    }

    public AdminDtos.Scope toResponse() { return new AdminDtos.Scope(warehouseId, scopeRole); }

    public static class Id implements Serializable {
        private Long userId;
        private Long warehouseId;
        public Id() {}
        public Id(Long userId, Long warehouseId) {
            this.userId = userId;
            this.warehouseId = warehouseId;
        }
        @Override
        public boolean equals(Object other) {
            return other instanceof Id that && Objects.equals(userId, that.userId)
                    && Objects.equals(warehouseId, that.warehouseId);
        }
        @Override
        public int hashCode() { return Objects.hash(userId, warehouseId); }
    }
}
