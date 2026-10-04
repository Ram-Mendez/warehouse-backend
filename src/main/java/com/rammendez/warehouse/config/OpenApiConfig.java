package com.rammendez.warehouse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenApiCustomizer operationDocumentation() {
        return api ->
                api.getPaths()
                        .forEach(
                                (path, item) ->
                                        item.readOperationsMap()
                                                .forEach(
                                                        (method, operation) -> {
                                                            boolean publicEndpoint =
                                                                    method
                                                                                    == PathItem
                                                                                            .HttpMethod
                                                                                            .POST
                                                                            && (path.equals(
                                                                                            "/api/v1/auth/login")
                                                                                    || path.equals(
                                                                                            "/api/v1/auth/refresh")
                                                                                    || path.equals(
                                                                                            "/api/v1/contact"));
                                                            operation.description(
                                                                    publicEndpoint
                                                                            ? "Public endpoint."
                                                                                  + " Credentials"
                                                                                  + " and personal"
                                                                                  + " message"
                                                                                  + " content are"
                                                                                  + " never logged."
                                                                            : "Requires "
                                                                                    + permission(
                                                                                            path,
                                                                                            method)
                                                                                    + ". Warehouse"
                                                                                    + " operations"
                                                                                    + " also"
                                                                                    + " require"
                                                                                    + " assigned"
                                                                                    + " scope;"
                                                                                    + " stock"
                                                                                    + " writes"
                                                                                    + " require"
                                                                                    + " OPERATOR,"
                                                                                    + " APPROVER or"
                                                                                    + " MANAGER"
                                                                                    + " scope.");
                                                            operation
                                                                    .getResponses()
                                                                    .addApiResponse(
                                                                            "400",
                                                                            new ApiResponse()
                                                                                    .description(
                                                                                            "Invalid"
                                                                                                + " request"
                                                                                                + " fields"
                                                                                                + " or format"
                                                                                                + " (ProblemDetail)"));
                                                            if (!publicEndpoint) {
                                                                operation
                                                                        .getResponses()
                                                                        .addApiResponse(
                                                                                "401",
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                "Missing,"
                                                                                                    + " expired"
                                                                                                    + " or invalid"
                                                                                                    + " JWT (ProblemDetail)"));
                                                                operation
                                                                        .getResponses()
                                                                        .addApiResponse(
                                                                                "403",
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                "Permission"
                                                                                                    + " or warehouse"
                                                                                                    + " scope"
                                                                                                    + " denied"
                                                                                                    + " (ProblemDetail)"));
                                                            } else if (path.contains("/auth/")) {
                                                                operation
                                                                        .getResponses()
                                                                        .addApiResponse(
                                                                                "401",
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                "Invalid"
                                                                                                    + " credentials"
                                                                                                    + " or refresh"
                                                                                                    + " token"
                                                                                                    + " (ProblemDetail)"));
                                                            }
                                                            if (path.contains("{")) {
                                                                operation
                                                                        .getResponses()
                                                                        .addApiResponse(
                                                                                "404",
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                "Resource"
                                                                                                    + " not found"
                                                                                                    + " (ProblemDetail)"));
                                                            }
                                                            if (method != PathItem.HttpMethod.GET) {
                                                                operation
                                                                        .getResponses()
                                                                        .addApiResponse(
                                                                                "409",
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                "Duplicate,"
                                                                                                    + " insufficient"
                                                                                                    + " stock,"
                                                                                                    + " invalid"
                                                                                                    + " transition"
                                                                                                    + " or concurrent"
                                                                                                    + " change"
                                                                                                    + " (ProblemDetail)"));
                                                            }
                                                        }));
    }

    private String permission(String path, PathItem.HttpMethod method) {
        boolean read = method == PathItem.HttpMethod.GET;
        if (path.contains("/auth/")) {
            return "a valid access JWT";
        }
        if (path.contains("/admin/")) {
            return "PERM_USER_MANAGE";
        }
        if (path.contains("/audit/")) {
            return "PERM_AUDIT_READ";
        }
        if (path.contains("/inventory")) {
            return "PERM_INVENTORY_READ";
        }
        if (path.contains("/categories")) {
            return read ? "PERM_CATEGORY_READ" : "PERM_CATEGORY_WRITE";
        }
        if (path.contains("/suppliers") && !path.contains("/products")) {
            return read ? "PERM_SUPPLIER_READ" : "PERM_SUPPLIER_WRITE";
        }
        if (path.contains("/products")) {
            return read ? "PERM_PRODUCT_READ" : "PERM_PRODUCT_WRITE";
        }
        if (path.contains("/warehouses")) {
            return read ? "PERM_WAREHOUSE_READ" : "PERM_WAREHOUSE_MANAGE";
        }
        if (path.contains("/transfers")) {
            return "PERM_STOCK_TRANSFER";
        }
        if (path.contains("/movements")) {
            if (read) {
                return "PERM_MOVEMENT_READ";
            }
            if (path.endsWith("/issue")) {
                return "PERM_STOCK_ISSUE";
            }
            if (path.endsWith("/receipt") || path.endsWith("/return")) {
                return "PERM_STOCK_RECEIVE";
            }
            return "PERM_INVENTORY_ADJUST";
        }
        if (path.contains("/purchase-orders")) {
            if (read) {
                return "PERM_PURCHASE_READ";
            }
            if (path.endsWith("/receive")) {
                return "PERM_PURCHASE_RECEIVE";
            }
            if (path.endsWith("/approve")) {
                return "PERM_PURCHASE_APPROVE";
            }
            return "PERM_PURCHASE_CREATE";
        }
        return read ? "PERM_CONTACT_READ" : "PERM_CONTACT_MANAGE";
    }

    @Bean
    OpenAPI warehouseApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Warehouse API")
                                .version("1.0")
                                .description(
                                        "Transactional inventory ledger. Permissions and assigned"
                                            + " warehouse scopes are required; see README endpoint"
                                            + " map."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
