package com.rammendez.warehouse.supplier;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SupplierService {
    private final SupplierRepository repository;
    private final AuditService audit;

    public SupplierService(SupplierRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('PERM_SUPPLIER_READ')")
    public PageResponse<SupplierDtos.Response> list(int page, int size) {
        return repository.list(page, size);
    }

    @PreAuthorize("hasAuthority('PERM_SUPPLIER_READ')")
    public SupplierDtos.Response get(long id) {
        return repository.get(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_WRITE')")
    public SupplierDtos.Response create(SupplierDtos.Input input) {
        long id = repository.insert(input);
        audit.event("SUPPLIER_CREATED", "supplier", id);
        return repository.get(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_WRITE')")
    public SupplierDtos.Response update(long id, SupplierDtos.Input input) {
        repository.get(id);
        repository.update(id, input);
        audit.event("SUPPLIER_UPDATED", "supplier", id);
        return repository.get(id);
    }
}
