package com.rammendez.warehouse.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("access")
public class AccessControl {
    private final SecurityRepository repository;

    public AccessControl(SecurityRepository repository) {
        this.repository = repository;
    }

    public long getAuthenticatedUserId() {
        return Long.parseLong(requireJwtAuthentication().getToken().getSubject());
    }

    public JwtAuthenticationToken requireJwtAuthentication() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Authentication required");
        }
        return token;
    }

    public void requirePermission(String code) {
        if (requireJwtAuthentication().getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(code))) {
            throw new AccessDeniedException("Permission denied");
        }
    }

    public void requireWarehouseScope(long warehouseId, boolean write) {
        if (!repository.hasWarehouseScopeForRequestedAccess(getAuthenticatedUserId(), warehouseId, write)) {
            throw new AccessDeniedException("Warehouse scope denied");
        }
    }
}
