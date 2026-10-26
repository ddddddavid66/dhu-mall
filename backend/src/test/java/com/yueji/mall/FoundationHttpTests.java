package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.HashSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationHttpTests extends MySqlTestSupport {
    @Autowired TestRestTemplate client;
    @Autowired OpenAPI contract;
    @LocalServerPort int serverPort;

    @Test
    void healthProbesDoNotPretendDatabaseOrBusinessIsReady() {
        for (String probe : Set.of("liveness", "readiness")) {
            var response = client.getForEntity("/actuator/health/" + probe, JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        }
    }

    @Test
    void everyDeclaredBusinessOperationFailsClosedWithoutFakeSuccess() {
        Set<String> ids = new HashSet<>();
        contract.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            ids.add(operation.getOperationId());
            if ("IMPLEMENTED".equals(operation.getExtensions().get("x-implementation-status"))) return;
            String concretePath = path.replaceAll("\\{duty}", "MERCHANT").replaceAll("\\{[^}]+}", "1");
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Request-Id", "contract-test-123");
            var response = client.exchange(concretePath, HttpMethod.valueOf(method.name()),
                    new HttpEntity<>("{}", headers), JsonNode.class);
            boolean disabled = operation.getOperationId().equals("API-34");
            assertThat(response.getStatusCode().value()).as(operation.getOperationId()).isEqualTo(disabled ? 404 : 501);
            JsonNode body = response.getBody();
            assertThat(body.path("code").asText()).isEqualTo(disabled ? "RESOURCE_NOT_FOUND" : "NOT_IMPLEMENTED");
            assertThat(body.has("data") && body.get("data").isNull()).isTrue();
            assertThat(body.path("traceId").asText()).isEqualTo("contract-test-123");
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("contract-test-123");
        }));
        assertThat(ids).hasSize(92);
    }

    @Test
    void everyImplementedProtectedOperationRejectsAnonymousRequests() {
        int[] protectedOperations = {0};
        contract.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (!"IMPLEMENTED".equals(operation.getExtensions().get("x-implementation-status"))) return;
            var security = operation.getSecurity() == null ? contract.getSecurity() : operation.getSecurity();
            if (security == null || security.isEmpty()) return;
            protectedOperations[0]++;

            String concretePath = path.replaceAll("\\{duty}", "MERCHANT").replaceAll("\\{[^}]+}", "1");
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Request-Id", "contract-anonymous-123");
            var response = client.exchange(concretePath, HttpMethod.valueOf(method.name()),
                    new HttpEntity<>("{}", headers), JsonNode.class);

            int status = response.getStatusCode().value();
            boolean disabled = operation.getOperationId().equals("API-34");
            if (disabled) {
                assertThat(status).as(operation.getOperationId()).isEqualTo(404);
            } else {
                assertThat(status).as(operation.getOperationId()).isIn(401, 403);
            }
            String expectedCode = disabled ? "RESOURCE_NOT_FOUND" : status == 403 ? "CSRF_INVALID" : "AUTH_REQUIRED";
            assertThat(response.getBody().path("code").asText()).as(operation.getOperationId()).isEqualTo(expectedCode);
            assertThat(response.getBody().get("data").isNull()).isTrue();
            assertThat(response.getBody().path("traceId").asText()).isEqualTo("contract-anonymous-123");
        }));
        assertThat(protectedOperations[0]).as("OpenAPI 中声明的受保护已实现操作").isEqualTo(84);
    }

    @Test
    void openapiExposesExactlyTheDeclaredSkeleton() {
        var response = client.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode doc = response.getBody();
        assertThat(doc.path("openapi").asText()).startsWith("3.1");
        int operations = 0;
        for (JsonNode item : doc.path("paths")) {
            for (JsonNode operation : item) {
                if (operation.has("operationId")) {
                    operations++;
                    assertThat(operation.path("x-implementation-status").asText()).isIn("IMPLEMENTED", "NOT_IMPLEMENTED", "DISABLED");
                    if (!"IMPLEMENTED".equals(operation.path("x-implementation-status").asText())) {
                        assertThat(operation.path("responses").has("200")).isFalse();
                        assertThat(operation.path("responses").has("201")).isFalse();
                    }
                }
            }
        }
        assertThat(operations).isEqualTo(92);
    }

    @Test
    void unknownAndWrongMethodUseUniformErrorsAndRegenerateInvalidTraceId() {
        var headers = new HttpHeaders();
        headers.set("X-Request-Id", "bad trace id");
        var unknown = client.exchange("/api/v1/unknown", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().path("traceId").asText()).matches("[A-Za-z0-9_-]{8,64}").isNotEqualTo("bad trace id");
        var wrongMethod = client.exchange("/api/v1/products", HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
        assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
        assertThat(wrongMethod.getBody().path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void corsPreflightAllowsOnlyConfiguredOrigin() throws Exception {
        // 使用原始 HTTP 客户端，确保 Origin 不会被 HttpURLConnection 的受限头规则丢弃。
        try (var http = HttpClient.newHttpClient()) {
            var uri = URI.create("http://127.0.0.1:" + serverPort + "/api/v1/auth/login");
            var allowed = HttpRequest.newBuilder(uri).header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = http.send(allowed, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
            var denied = HttpRequest.newBuilder(uri).header("Origin", "https://untrusted.invalid")
                    .header("Access-Control-Request-Method", "POST").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertThat(http.send(denied, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
