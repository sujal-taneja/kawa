package dev.kawa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void theApiDescribesItself() {
        Map<String, Object> spec = http.getForObject("/v3/api-docs", Map.class);

        assertThat(((Map<String, Object>) spec.get("info")).get("title")).isEqualTo("Kawa API");
        assertThat((Map<String, Object>) spec.get("paths")).containsKeys(
                "/api/v1/queues/{queue}/jobs",
                "/api/v1/queues/{queue}/take",
                "/api/v1/jobs/{id}",
                "/api/v1/jobs/{id}/ack",
                "/api/v1/jobs/{id}/nack",
                "/api/v1/jobs/{id}/extend");
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> page = http.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Swagger UI");
    }
}
