package com.rammendez.warehouse.product;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProductDtos.Response> listFilteredAndSortedProducts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sort) {
        return service.listFilteredAndSortedProducts(search, sku, categoryId, supplierId, active, page, size, sort);
    }

    @GetMapping("/{id}")
    public ProductDtos.Response getProduct(@PathVariable long id) {
        return service.getProduct(id);
    }

    @GetMapping("/{id}/suppliers")
    public PageResponse<ProductDtos.SupplierLink> listProductSupplierLinks(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listProductSupplierLinks(id, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDtos.Response create(@Valid @RequestBody ProductDtos.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public ProductDtos.Response updateProductWithRequiredCurrentVersion(
            @PathVariable long id, @Valid @RequestBody ProductDtos.Input input) {
        return service.updateProductWithRequiredCurrentVersion(id, input);
    }
}
