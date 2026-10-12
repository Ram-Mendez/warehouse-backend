package com.rammendez.warehouse.category;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Serialize hierarchy edits so concurrent reparenting cannot pass separate cycle checks.
    // A single transaction-scoped lock trades write throughput for a simple tree invariant.
    @Query(value = "select cast(pg_advisory_xact_lock(4815162342) as text)", nativeQuery = true)
    String acquireHierarchyTransactionLock();

    default void lockHierarchy() {
        acquireHierarchyTransactionLock();
    }

    default CategoryDtos.Response getCategory(long id) {
        return findById(id).orElseThrow(() -> BusinessException.missing("Category")).toResponse();
    }

    default PageResponse<CategoryDtos.Response> list(int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findAll(PageRequest.of(page, size, Sort.by("id")));
        return new PageResponse<>(result.map(Category::toResponse).getContent(), result.getTotalElements(), page, size);
    }
}
