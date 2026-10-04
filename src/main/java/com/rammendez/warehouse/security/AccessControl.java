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

    public long userId() {
        return Long.parseLong(jwt().getToken().getSubject());
    }

    public JwtAuthenticationToken jwt() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Authentication required");
        }
        return token;
    }

    public void permission(String code) {
        if (jwt().getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(code))) {
            throw new AccessDeniedException("Permission denied");
        }
    }

    public void warehouse(long warehouseId, boolean write) {
        if (!repository.hasScope(userId(), warehouseId, write)) {
            throw new AccessDeniedException("Warehouse scope denied");
        }
    }
}
