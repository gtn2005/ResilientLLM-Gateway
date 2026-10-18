package dev.resilient.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilient.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DjlMiniLmEmbeddingServiceTest {
  @Test
  void embedsWithLocalModelWhenProvided() {
    String path = System.getenv("TEST_MINILM_PATH");
    Assumptions.assumeTrue(path != null && !path.isBlank(), "Local ONNX model not provided");
    GatewayProperties settings =
        new GatewayProperties(
            "gateway",
            "test",
            Duration.ofSeconds(1),
            new GatewayProperties.RateLimit(10, 1),
            new GatewayProperties.Cache(true, 0.92, Duration.ofHours(1), 0, path),
            new GatewayProperties.Mock(0, 0),
            List.of());
    DjlMiniLmEmbeddingService service = new DjlMiniLmEmbeddingService(settings);
    try {
      assertThat(service.ready()).isTrue();
      float[] first = service.embed("Explain provider failover in plain language.");
      float[] repeat = service.embed("Explain provider failover in plain language.");
      assertThat(first).hasSize(384);
      assertThat(repeat).containsExactly(first);
    } finally {
      service.close();
    }
  }
}
