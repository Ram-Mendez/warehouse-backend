package com.rammendez.warehouse.supplier;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<SupplierDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public SupplierDtos.Response getSupplier(@PathVariable long id) {
        return service.getSupplier(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierDtos.Response create(@Valid @RequestBody SupplierDtos.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public SupplierDtos.Response update(
            @PathVariable long id, @Valid @RequestBody SupplierDtos.Input input) {
        return service.update(id, input);
    }
}
