package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "logging.level.root=WARN"})
class ProductionIsolationTests extends MySqlTestSupport {
    @Autowired TestRestTemplate client;
    @LocalManagementPort int managementPort;
    @Autowired com.yueji.mall.identity.service.DevSeedService seeds;

    @Test
    void productionDoesNotExposeDocsOrMockPaymentAndUsesSeparateManagementPort() {
        assertThat(client.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(client.postForEntity("/api/v1/payments/1/mock-confirm", null, JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(client.getForEntity("/actuator/health/liveness", String.class).getStatusCode().value()).isEqualTo(404);
        var health = client.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/liveness", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThatThrownBy(()->seeds.seed(java.nio.file.Path.of("../.artifacts/should-not-be-written.json"))).isInstanceOf(IllegalStateException.class);
    }
}
