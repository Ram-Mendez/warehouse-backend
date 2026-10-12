package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrainingBatchTwoIntegrationTest extends PostgresIntegrationTest {
    @Test
    void productActiveFiltersReturnOnlyMatchingProductsWithinRequestedCategory() throws Exception {
        var input = new HashMap<>(createProductWithSupplierRequestBody("INACTIVE-" + suffix, "Retired product"));
        input.put("active", false);
        long inactiveId = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/products", admin, input, 201)).path("id").asLong();

        long otherCategory = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/categories", admin,
                Map.of("code", "OTHER-" + suffix, "name", "Other category", "active", true), 201))
                .path("id").asLong();
        for (boolean active : List.of(false, true)) {
            var otherProduct = new HashMap<>(createProductWithSupplierRequestBody(
                    "OTHER-" + active + "-" + suffix, "Product in another category"));
            otherProduct.put("categoryId", otherCategory);
            otherProduct.put("active", active);
            executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, otherProduct, 201);
        }

        for (boolean active : List.of(false, true)) {
            var result = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                    "GET", "/api/v1/products?categoryId=" + category + "&active=" + active,
                    worker, null, 200));
            assertThat(result.path("totalElements").asLong()).isEqualTo(1);
            assertThat(result.path("content").size()).isEqualTo(1);
            var matchingProduct = result.path("content").get(0);
            assertThat(matchingProduct.path("id").asLong()).isEqualTo(active ? product : inactiveId);
            assertThat(matchingProduct.path("active").isBoolean()).isTrue();
            assertThat(matchingProduct.path("active").asBoolean()).isEqualTo(active);
            assertThat(matchingProduct.path("categoryId").asLong()).isEqualTo(category);
        }
        var categoryProducts = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/products?categoryId=" + category, worker, null, 200));
        assertThat(categoryProducts.path("totalElements").asLong()).isEqualTo(2);
        var categoryProductIds = new ArrayList<Long>();
        for (var matchingProduct : categoryProducts.path("content")) {
            categoryProductIds.add(matchingProduct.path("id").asLong());
        }
        assertThat(categoryProductIds).containsExactlyInAnyOrder(product, inactiveId);
    }

    @Test
    void contactSubmissionAcceptsTenCharacterMessage() throws Exception {
        String message = "Need boxes";
        assertThat(message).hasSize(10);
        var accepted = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/contact", null,
                Map.of("name", "Training visitor", "email", "visitor@example.test",
                        "subject", "Delivery", "message", message), 201));
        String id = accepted.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(accepted.path("status").asText()).isEqualTo("NEW");
        var stored = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/contact/" + id, admin, null, 200));
        assertThat(stored.path("id").asText()).isEqualTo(id);
        assertThat(stored.path("message").asText()).isEqualTo(message);
        assertThat(stored.path("status").asText()).isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_message where id=?::uuid and message=? and status='NEW'",
                Integer.class, id, message)).isEqualTo(1);
    }

    @Test
    void supplierCreationAndReadPreserveDistinctSubmittedEmailAndPhone() throws Exception {
        var created = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/suppliers", admin,
                Map.of("code", "PHONE-" + suffix, "name", "Training supplier",
                        "email", "orders@example.test", "phone", "+34 910 123 456", "active", true), 201));
        long id = created.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(created.path("email").asText()).isEqualTo("orders@example.test");
        assertThat(created.path("phone").asText()).isEqualTo("+34 910 123 456")
                .isNotEqualTo(created.path("email").asText());
        assertThat(jdbc.queryForObject("select phone from supplier where id=?", String.class, id))
                .isEqualTo("+34 910 123 456");
        var response = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/suppliers/" + id, worker, null, 200));
        assertThat(response.path("email").asText()).isEqualTo("orders@example.test");
        assertThat(response.path("id").asLong()).isEqualTo(id);
        assertThat(response.path("phone").asText()).isEqualTo("+34 910 123 456")
                .isNotEqualTo(response.path("email").asText());
    }

    @Test
    void locationPagesReturnEachLocationOnceInIdOrder() throws Exception {
        var ids = new ArrayList<Long>();
        ids.add(northLocation);
        for (String code : List.of("AISLE-A", "AISLE-B", "AISLE-C")) {
            ids.add(parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                    "POST", "/api/v1/warehouses/" + north + "/locations", admin,
                    Map.of("code", code, "active", true), 201)).path("id").asLong());
        }
        var returnedIds = new ArrayList<Long>();
        for (int page = 0; page < 2; page++) {
            var result = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                    "GET", "/api/v1/warehouses/" + north + "/locations?page=" + page + "&size=2",
                    worker, null, 200));
            assertThat(result.path("totalElements").asLong()).isEqualTo(4);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("size").asInt()).isEqualTo(2);
            assertThat(result.path("content").size()).isEqualTo(2);
            var pageIds = new ArrayList<Long>();
            for (var location : result.path("content")) {
                assertThat(location.path("warehouseId").asLong()).isEqualTo(north);
                pageIds.add(location.path("id").asLong());
            }
            assertThat(pageIds).containsExactlyElementsOf(ids.subList(page * 2, page * 2 + 2));
            returnedIds.addAll(pageIds);
        }
        assertThat(returnedIds).doesNotHaveDuplicates().containsExactlyElementsOf(ids);
    }

    @Test
    void approvedPurchaseCanBeCancelledBeforeReceipt() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(new BigDecimal("5"));
        BigDecimal stockBefore = getFixtureProductTotalStockInWarehouse(north);
        assertThat(stockBefore).isEqualByComparingTo("5");
        String id = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/purchase-orders", manager,
                Map.of("supplierId", supplier, "warehouseId", north, "orderNumber", "CANCEL-" + suffix),
                201)).path("id").asText();
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/lines", manager,
                Map.of("productId", product, "quantity", 3, "unitCost", 2.5), 200);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        var approved = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/purchase-orders/" + id + "/approve", manager, null, 200));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("lines").get(0).path("receivedQuantity").decimalValue()).isZero();
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo(stockBefore);
        var cancelled = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/purchase-orders/" + id + "/cancel", manager, null, 200));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        var stored = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/purchase-orders/" + id, manager, null, 200));
        assertThat(stored.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stored.path("lines").get(0).path("receivedQuantity").decimalValue()).isZero();
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo(stockBefore);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where purchase_order_id=?::uuid",
                Integer.class, id)).isZero();
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo(stockBefore);
    }
}
