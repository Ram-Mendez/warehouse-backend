package com.rammendez.warehouse.supplier;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    default SupplierDtos.Response getSupplier(long id) {
        return findById(id).orElseThrow(() -> BusinessException.missing("Supplier")).toResponse();
    }

    default PageResponse<SupplierDtos.Response> list(int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findAll(PageRequest.of(page, size, Sort.by("id")));
        return new PageResponse<>(result.map(Supplier::toResponse).getContent(), result.getTotalElements(), page, size);
    }
}
