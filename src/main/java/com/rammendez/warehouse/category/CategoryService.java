package com.rammendez.warehouse.category;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.common.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository repository;
    private final AuditService audit;

    public CategoryService(CategoryRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('PERM_CATEGORY_READ')")
    public PageResponse<CategoryDtos.Response> list(int page, int size) {
        return repository.list(page, size);
    }

    @PreAuthorize("hasAuthority('PERM_CATEGORY_READ')")
    public CategoryDtos.Response getCategory(long id) {
        return repository.getCategory(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_CATEGORY_WRITE')")
    public CategoryDtos.Response create(CategoryDtos.Input input) {
        repository.lockHierarchy();
        validateOptionalCategoryParentAndRejectHierarchyCycles(input.parentId(), null);
        long id = repository.insert(input);
        audit.recordAuditEventWithActorAndWarehouseReferences("CATEGORY_CREATED", "category", id);
        return repository.getCategory(id);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_CATEGORY_WRITE')")
    public CategoryDtos.Response update(long id, CategoryDtos.Input input) {
        repository.lockHierarchy();
        repository.getCategory(id);
        validateOptionalCategoryParentAndRejectHierarchyCycles(input.parentId(), id);
        repository.update(id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("CATEGORY_UPDATED", "category", id);
        return repository.getCategory(id);
    }

    private void validateOptionalCategoryParentAndRejectHierarchyCycles(Long parentId, Long id) {
        if (parentId == null) {
            return;
        }
        var parent = repository.getCategory(parentId);
        java.util.Set<Long> visited = new java.util.HashSet<>();
        while (parent != null) {
            if (!visited.add(parent.id()) || java.util.Objects.equals(id, parent.id())) {
                throw BusinessException.conflict("Category hierarchy cannot contain a cycle");
            }
            parent = parent.parentId() == null ? null : repository.getCategory(parent.parentId());
        }
    }
}
