package dev.resilient.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String mode,
    String adminToken,
    Duration requestTimeout,
    RateLimit rateLimit,
    Cache cache,
    Mock mock,
    List<Provider> providers) {
  public record RateLimit(long capacity, long refillPerSecond) {}

  public record Cache(
      boolean enabled,
      double threshold,
      Duration ttl,
      double auditSampleRate,
      String embeddingModelPath) {}

  public record Mock(long latencyMs, double errorRate) {}

  public record Provider(String id, String type, String baseUrl, String model, String apiKey) {}
}
