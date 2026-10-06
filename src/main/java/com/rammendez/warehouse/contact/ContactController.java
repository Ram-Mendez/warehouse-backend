package com.rammendez.warehouse.contact;

import com.rammendez.warehouse.common.PageResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contact")
public class ContactController {
    private final ContactService service;

    public ContactController(ContactService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    public ContactDtos.Accepted submitContactMessageAndReturnAcknowledgement(@Valid @RequestBody ContactDtos.Input input) {
        return service.submitContactMessageAndReturnAcknowledgement(input);
    }

    @GetMapping
    public PageResponse<ContactDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public ContactDtos.Response getContactMessage(@PathVariable UUID id) {
        return service.getContactMessage(id);
    }

    @PatchMapping("/{id}/status")
    public ContactDtos.Response updateContactMessageStatusAndAssignActor(
            @PathVariable UUID id, @Valid @RequestBody ContactDtos.StatusInput input) {
        return service.updateContactMessageStatusAndAssignActor(id, input.status());
    }
}
