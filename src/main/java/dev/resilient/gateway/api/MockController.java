package dev.resilient.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.resilient.gateway.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gateway.mode", havingValue = "mock")
public class MockController {
  private final GatewayProperties.Mock settings;
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final String name;

  public MockController(GatewayProperties properties, @Value("${MOCK_NAME:mock}") String name) {
    this.settings = properties.mock();
    this.name = name;
  }

  @PostMapping("/v1/chat/completions")
  public ResponseEntity<?> complete(@RequestBody JsonNode request) throws InterruptedException {
    if (!enabled.get()) {
      return ResponseEntity.status(503)
          .body(Map.of("error", Map.of("message", name + " disabled", "type", "mock_outage")));
    }
    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, settings.latencyMs() / 3 + 1));
    Thread.sleep(settings.latencyMs() + jitter);
    if (ThreadLocalRandom.current().nextDouble() < settings.errorRate()) {
      return ResponseEntity.status(503)
          .body(Map.of("error", Map.of("message", "Injected mock error", "type", "mock_error")));
    }
    String prompt =
        request.path("messages").isArray() && !request.path("messages").isEmpty()
            ? request
                .path("messages")
                .get(request.path("messages").size() - 1)
                .path("content")
                .asText()
            : "";
    String content = "Simulation complete. " + prompt;
    return ResponseEntity.ok(
        Map.of(
            "id",
            "chatcmpl-" + UUID.randomUUID(),
            "object",
            "chat.completion",
            "created",
            Instant.now().getEpochSecond(),
            "model",
            request.path("model").asText("mock-1"),
            "choices",
            List.of(
                Map.of(
                    "index",
                    0,
                    "message",
                    Map.of("role", "assistant", "content", content),
                    "finish_reason",
                    "stop")),
            "usage",
            Map.of(
                "prompt_tokens",
                Math.max(1, prompt.length() / 4),
                "completion_tokens",
                Math.max(1, content.length() / 4),
                "total_tokens",
                Math.max(2, (prompt.length() + content.length()) / 4))));
  }

  @GetMapping("/admin/state")
  public Map<String, Object> state() {
    return Map.of(
        "name",
        name,
        "enabled",
        enabled.get(),
        "latencyMs",
        settings.latencyMs(),
        "errorRate",
        settings.errorRate());
  }

  @PostMapping("/admin/state")
  public Map<String, Object> setState(@RequestBody Map<String, Boolean> body) {
    enabled.set(Boolean.TRUE.equals(body.get("enabled")));
    return state();
  }
}
