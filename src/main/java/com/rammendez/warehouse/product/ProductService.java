package com.rammendez.warehouse.product;

import com.rammendez.warehouse.audit.AuditService;
import com.rammendez.warehouse.category.CategoryRepository;
import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.supplier.SupplierRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository repository;
    private final ProductQueryRepository queries;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final AuditService audit;

    public ProductService(
            ProductRepository repository,
            ProductQueryRepository queries,
            CategoryRepository categories,
            SupplierRepository suppliers,
            AuditService audit) {
        this.repository = repository;
        this.queries = queries;
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
        return queries.listProductSupplierLinks(id, page, size);
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
        return queries.listFilteredAndSortedProducts(search, sku, categoryId, supplierId, active, page, size, sort);
    }

    @Transactional
    @PreAuthorize("hasAuthority('PERM_PRODUCT_WRITE')")
    public ProductDtos.Response create(ProductDtos.Input input) {
        var product = new Product();
        validateReferencesAndApplyProductInput(product, input);
        repository.saveAndFlush(product);
        queries.upsertProductSupplierCostIfSupplierProvided(product.id, input.supplierId(), input.unitCost());
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
        repository.flush();
        queries.upsertProductSupplierCostIfSupplierProvided(id, input.supplierId(), input.unitCost());
        audit.recordAuditEventWithActorAndWarehouseReferences("PRODUCT_UPDATED", "product", id);
        return createProductResponse(product);
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
