package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrainingBatchTwoIntegrationTest extends PostgresIntegrationTest {
    @Test
    void inactiveProductListContainsOnlyInactiveProducts() throws Exception {
        var input = new HashMap<>(createProductWithSupplierRequestBody("INACTIVE-" + suffix, "Retired product"));
        input.put("active", false);
        long inactiveId = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/products", admin, input, 201)).path("id").asLong();

        var result = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/products?categoryId=" + category + "&active=false", worker, null, 200));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("content").size()).isEqualTo(1);
        assertThat(result.path("content").get(0).path("id").asLong()).isEqualTo(inactiveId);
        assertThat(result.path("content").get(0).path("active").asBoolean()).isFalse();
    }

    @Test
    void contactSubmissionAcceptsTenCharacterMessage() throws Exception {
        var accepted = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/contact", null,
                Map.of("name", "Training visitor", "email", "visitor@example.test",
                        "subject", "Delivery", "message", "Need boxes"), 201));
        assertThat(accepted.path("status").asText()).isEqualTo("NEW");
        var stored = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/contact/" + accepted.path("id").asText(), admin, null, 200));
        assertThat(stored.path("message").asText()).isEqualTo("Need boxes");
    }

    @Test
    void supplierReadReturnsSubmittedPhoneNumber() throws Exception {
        var created = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/suppliers", admin,
                Map.of("code", "PHONE-" + suffix, "name", "Training supplier",
                        "email", "orders@example.test", "phone", "+34 910 123 456", "active", true), 201));
        long id = created.path("id").asLong();
        assertThat(jdbc.queryForObject("select phone from supplier where id=?", String.class, id))
                .isEqualTo("+34 910 123 456");
        var response = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/suppliers/" + id, worker, null, 200));
        assertThat(response.path("email").asText()).isEqualTo("orders@example.test");
        assertThat(response.path("phone").asText()).isEqualTo("+34 910 123 456");
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
            for (var location : result.path("content")) {
                returnedIds.add(location.path("id").asLong());
            }
        }
        assertThat(returnedIds).containsExactlyElementsOf(ids);
    }

    @Test
    void approvedPurchaseCanBeCancelledBeforeReceipt() throws Exception {
        String id = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/purchase-orders", manager,
                Map.of("supplierId", supplier, "warehouseId", north, "orderNumber", "CANCEL-" + suffix),
                201)).path("id").asText();
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/lines", manager,
                Map.of("productId", product, "quantity", 3, "unitCost", 2.5), 200);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/approve", manager, null, 200);
        var cancelled = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "POST", "/api/v1/purchase-orders/" + id + "/cancel", manager, null, 200));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        var stored = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/purchase-orders/" + id, manager, null, 200));
        assertThat(stored.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where purchase_order_id=?::uuid",
                Integer.class, id)).isZero();
        executeHttpRequestAndAssertStatus("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
    }
}
