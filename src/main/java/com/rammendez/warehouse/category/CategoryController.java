package com.rammendez.warehouse.category;

import com.rammendez.warehouse.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CategoryDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public CategoryDtos.Response get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDtos.Response create(@Valid @RequestBody CategoryDtos.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public CategoryDtos.Response update(
            @PathVariable long id, @Valid @RequestBody CategoryDtos.Input input) {
        return service.update(id, input);
    }
}
