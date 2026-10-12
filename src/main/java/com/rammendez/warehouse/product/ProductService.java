package com.rammendez.warehouse.product;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.category.CategoryRepository;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.supplier.SupplierRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// Read-only transactions avoid Hibernate dirty checking; write methods override this default.
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository repository;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final AuditService audit;

    public ProductService(
            ProductRepository repository,
            CategoryRepository categories,
            SupplierRepository suppliers,
            AuditService audit) {
        this.repository = repository;
        this.categories = categories;
        this.suppliers = suppliers;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_READ')")
    public ProductDtos.Response getProduct(long id) {
        return createProductResponse(getProductOrThrowNotFound(id));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_READ')")
    public PageResponse<ProductDtos.SupplierLink> listProductSupplierLinks(long id, int page, int size) {
        getProductOrThrowNotFound(id);
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = repository.findSupplierLinks(id, PageRequest.of(page, size));
        return new PageResponse<>(result.map(ProductSupplier::toResponse).getContent(), result.getTotalElements(), page, size);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_READ')")
    public PageResponse<ProductDtos.Response> listFilteredAndSortedProducts(
            String search,
            String sku,
            Long categoryId,
            Long supplierId,
            Boolean active,
            int page,
            int size,
            String sort) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        // Only the documented sort properties can reach Spring Data's generated ORDER BY.
        Sort order = switch (sort) {
            case "name", "sku" -> Sort.by(sort).and(Sort.by("id"));
            case "createdAt" -> Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
            case "id" -> Sort.by("id");
            default -> throw BusinessException.invalid("sort must be id, name, sku, or createdAt");
        };
        String pattern = search == null || search.isBlank() ? null
                : Sql.createEscapedContainsLikePattern(search.toLowerCase(java.util.Locale.ROOT));
        var result = repository.findFiltered(pattern, sku, categoryId, supplierId, active,
                PageRequest.of(page, size, order));
        return new PageResponse<>(result.map(this::createProductResponse).getContent(), result.getTotalElements(), page, size);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PRODUCT_WRITE')")
    public ProductDtos.Response create(ProductDtos.Input input) {
        var product = new Product();
        validateReferencesAndApplyProductInput(product, input);
        // Flush makes the product insert visible to the atomic supplier-link upsert.
        // Both still use the same transaction: flush does not commit either write.
        repository.saveAndFlush(product);
        upsertSupplierCostIfProvided(product.id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("PRODUCT_CREATED", "product", product.id);
        return createProductResponse(product);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PRODUCT_WRITE')")
    public ProductDtos.Response updateProductWithRequiredCurrentVersion(long id, ProductDtos.Input input) {
        var product = getProductOrThrowNotFound(id);
        if (input.version() == null || !input.version().equals(product.version)) {
            throw BusinessException.conflict("Product version is stale");
        }
        validateReferencesAndApplyProductInput(product, input);
        product.updatedAt = java.time.Instant.now();
        // Detect optimistic conflicts and obtain the new version before building the response.
        repository.flush();
        upsertSupplierCostIfProvided(id, input);
        audit.recordAuditEventWithActorAndWarehouseReferences("PRODUCT_UPDATED", "product", id);
        return createProductResponse(product);
    }

    private void upsertSupplierCostIfProvided(long productId, ProductDtos.Input input) {
        if (input.supplierId() != null) {
            repository.upsertSupplierCost(productId, input.supplierId(), input.unitCost());
        }
    }

    private Product getProductOrThrowNotFound(long id) {
        return repository.findById(id).orElseThrow(() -> BusinessException.missing("Product"));
    }

    private void validateReferencesAndApplyProductInput(Product product, ProductDtos.Input input) {
        if (input.unitCost() != null && input.supplierId() == null) {
            throw BusinessException.invalid("unitCost requires supplierId");
        }
        if (input.categoryId() != null) {
            categories.getCategory(input.categoryId());
        }
        if (input.supplierId() != null) {
            suppliers.getSupplier(input.supplierId());
        }
        product.sku = input.sku().trim();
        product.barcode = input.barcode();
        product.name = input.name().trim();
        product.description = input.description();
        product.categoryId = input.categoryId();
        product.unit = input.unit().trim();
        product.minimumStock = input.minimumStock();
        product.active = input.active();
    }

    private ProductDtos.Response createProductResponse(Product product) {
        return new ProductDtos.Response(
                product.id,
                product.sku,
                product.barcode,
                product.name,
                product.description,
                product.categoryId,
                product.unit,
                product.minimumStock,
                product.active,
                product.version,
                product.createdAt,
                product.updatedAt);
    }
}
