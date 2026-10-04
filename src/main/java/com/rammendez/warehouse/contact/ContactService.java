package com.rammendez.warehouse.contact;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.PageResponse;
import com.rammendez.warehouse.security.AccessControl;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ContactService {
    private final ContactRepository repository;
    private final AccessControl access;
    private final AuditService audit;

    public ContactService(ContactRepository repository, AccessControl access, AuditService audit) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public ContactDtos.Accepted submit(ContactDtos.Input input) {
        var message = repository.get(repository.insert(input));
        return new ContactDtos.Accepted(message.id(), message.status(), message.createdAt());
    }

    @PreAuthorize("hasAuthority('PERM_CONTACT_READ')")
    public ContactDtos.Response get(UUID id) {
        return repository.get(id);
    }

    @PreAuthorize("hasAuthority('PERM_CONTACT_READ')")
    public PageResponse<ContactDtos.Response> list(int page, int size) {
        return repository.list(page, size);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_CONTACT_MANAGE')")
    public ContactDtos.Response status(UUID id, ContactDtos.Status status) {
        repository.get(id);
        repository.status(id, status, access.userId());
        audit.event("CONTACT_STATUS_CHANGED", "contact_message", id);
        return repository.get(id);
    }
}
