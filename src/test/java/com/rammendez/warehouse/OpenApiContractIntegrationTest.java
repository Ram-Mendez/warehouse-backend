package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rammendez.warehouse.product.ProductDtos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractIntegrationTest {
    @DynamicPropertySource
    static void registerPostgres(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerPostgresContainerConnectionProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private JsonNode document;

    @BeforeAll
    void loadGeneratedOpenApi() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        document = mapper.readTree(json);
        Files.writeString(Path.of("target", "openapi-contract.json"), json);
    }

    @Test
    void everyControllerRequestBodyMatchesItsJavaType() {
        int operations = 0;
        int requestBodies = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.rammendez.warehouse")) {
                continue;
            }
            var bodyParameters = Arrays.stream(handler.getMethodParameters())
                    .filter(parameter -> parameter.hasParameterAnnotation(RequestBody.class))
                    .toList();
            for (String path : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    String operationName = method + " " + path;
                    JsonNode operation = document.path("paths").path(path)
                            .path(method.name().toLowerCase(Locale.ROOT));
                    assertThat(operation.isMissingNode()).as(operationName).isFalse();
                    operations++;
                    if (bodyParameters.isEmpty()) {
                        assertThat(operation.has("requestBody")).as(operationName).isFalse();
                        continue;
                    }
                    assertThat(bodyParameters).as(operationName).hasSize(1);
                    var parameter = bodyParameters.getFirst();
                    JsonNode body = operation.path("requestBody");
                    assertThat(body.path("required").asBoolean()).as(operationName)
                            .isEqualTo(parameter.getParameterAnnotation(RequestBody.class).required());
                    JsonNode mediaType = body.path("content").path("application/json");
                    assertThat(mediaType.isMissingNode()).as(operationName).isFalse();
                    assertSchemaMatchesType(mediaType.path("schema"), parameter.getGenericParameterType());
                    // Swagger UI should derive its example from this schema, without stale overrides.
                    assertThat(mediaType.has("example") || mediaType.has("examples"))
                            .as(operationName + " example overrides").isFalse();
                    requestBodies++;
                }
            }
        }
        assertThat(operations).isEqualTo(58);
        assertThat(requestBodies).isEqualTo(26);
    }

    @Test
    void productCreateAndUpdateExposeTheCompleteProductInput() {
        for (String[] endpoint : new String[][] {
                {"/api/v1/products", "post"}, {"/api/v1/products/{id}", "put"}}) {
            JsonNode schema = document.path("paths").path(endpoint[0]).path(endpoint[1])
                    .path("requestBody").path("content").path("application/json").path("schema");
            JsonNode product = resolve(schema);
            assertThat(product.path("properties").propertyNames()).containsExactlyInAnyOrder(
                    "sku", "barcode", "name", "description", "categoryId", "unit", "minimumStock",
                    "active", "supplierId", "unitCost", "version");
            assertThat(schema.path("$ref").asText())
                    .isEqualTo("#/components/schemas/" + schemaName(ProductDtos.Input.class));
            assertThat(product.path("required").valueStream().map(JsonNode::asText).toList())
                    .containsExactlyInAnyOrder("sku", "name", "unit", "minimumStock", "active");
        }
    }

    @Test
    void allGeneratedSchemaReferencesResolve() {
        assertReferencesResolve(document);
    }

    private void assertSchemaMatchesType(JsonNode schema, Type type) {
        if (type instanceof ParameterizedType generic
                && Collection.class.isAssignableFrom((Class<?>) generic.getRawType())) {
            assertThat(schema.path("type").asText()).isEqualTo("array");
            assertSchemaMatchesType(schema.path("items"), generic.getActualTypeArguments()[0]);
            return;
        }
        Class<?> javaType = (Class<?>) type;
        if (javaType.isRecord()) {
            assertThat(schema.path("$ref").asText()).as(javaType.getName())
                    .isEqualTo("#/components/schemas/" + schemaName(javaType));
            JsonNode properties = resolve(schema).path("properties");
            assertThat(properties.propertyNames()).as(javaType.getName()).containsExactlyInAnyOrder(
                    Arrays.stream(javaType.getRecordComponents()).map(component -> component.getName())
                            .toArray(String[]::new));
            for (var component : javaType.getRecordComponents()) {
                assertSchemaMatchesType(properties.path(component.getName()), component.getGenericType());
            }
            return;
        }
        String expectedType;
        if (javaType == String.class || javaType.isEnum()) {
            expectedType = "string";
        } else if (javaType == boolean.class || javaType == Boolean.class) {
            expectedType = "boolean";
        } else if (javaType == long.class || javaType == Long.class) {
            expectedType = "integer";
        } else if (javaType == BigDecimal.class) {
            expectedType = "number";
        } else {
            throw new AssertionError("Add schema verification for request type " + type);
        }
        assertThat(schema.path("type").asText()).as(type.getTypeName()).isEqualTo(expectedType);
        if (javaType.isEnum()) {
            assertThat(schema.path("enum").valueStream().map(JsonNode::asText).toList())
                    .containsExactly(Arrays.stream(javaType.getEnumConstants()).map(Object::toString)
                            .toArray(String[]::new));
        }
    }

    private static String schemaName(Class<?> type) {
        return type.getName().replace('$', '.');
    }

    private JsonNode resolve(JsonNode schema) {
        String ref = schema.path("$ref").asText();
        assertThat(ref).startsWith("#/components/schemas/");
        JsonNode resolved = document.at(ref.substring(1));
        assertThat(resolved.isMissingNode()).as(ref).isFalse();
        return resolved;
    }

    private void assertReferencesResolve(JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            resolve(node);
        }
        for (JsonNode child : node) {
            assertReferencesResolve(child);
        }
    }
}
