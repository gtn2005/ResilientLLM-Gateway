package dev.resilient.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.resilient.gateway.cache.EmbeddingService;
import dev.resilient.gateway.cache.SemanticCache;
import dev.resilient.gateway.config.GatewayProperties;
import dev.resilient.gateway.ratelimit.RedisTokenBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {
  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis/redis-stack-server:7.4.0-v8").withExposedPorts(6379);

  private StringRedisTemplate template;

  @BeforeEach
  void connect() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
  }

  @Test
  void tokenBucketIsAtomicUnderConcurrency() throws Exception {
    GatewayProperties settings = settings(10, 1);
    RedisTokenBucket bucket = new RedisTokenBucket(template, settings);
    var pool = Executors.newFixedThreadPool(20);
    try {
      List<Callable<Boolean>> tasks =
          java.util.stream.IntStream.range(0, 80)
              .mapToObj(i -> (Callable<Boolean>) () -> bucket.take("concurrent-key").allowed())
              .toList();
      long allowed =
          pool.invokeAll(tasks).stream()
              .filter(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception failure) {
                      throw new RuntimeException(failure);
                    }
                  })
              .count();
      assertThat(allowed).isEqualTo(10);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void vectorCacheHitsOnlyWithinTenantAndModelNamespace() throws Exception {
    EmbeddingService embeddings =
        new EmbeddingService() {
          @Override
          public float[] embed(String text) {
            float[] vector = new float[384];
            vector[0] = 1;
            return vector;
          }

          @Override
          public boolean ready() {
            return true;
          }
        };
    SemanticCache cache =
        new SemanticCache(
            template, embeddings, settings(10, 1), new ObjectMapper(), new SimpleMeterRegistry());
    JsonNode response =
        new ObjectMapper().readTree("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
    var initial = cache.prepare("tenant-a", "model-a", 0, "What is failover?");
    cache.store(initial, response, "primary");
    assertThat(
            cache.lookup(
                cache.prepare("tenant-a", "model-a", 0, "Explain failover"), "Explain failover"))
        .isPresent();
    assertThat(
            cache.lookup(
                cache.prepare("tenant-b", "model-a", 0, "Explain failover"), "Explain failover"))
        .isEmpty();
    assertThat(
            cache.lookup(
                cache.prepare("tenant-a", "model-b", 0, "Explain failover"), "Explain failover"))
        .isEmpty();
    assertThat(cache.prepare("tenant-a", "model-a", 0.8, "Explain failover")).isNull();
  }

  private GatewayProperties settings(long capacity, long refill) {
    return new GatewayProperties(
        "gateway",
        "test",
        Duration.ofSeconds(1),
        new GatewayProperties.RateLimit(capacity, refill),
        new GatewayProperties.Cache(true, 0.92, Duration.ofHours(1), 0.0, "/unused"),
        new GatewayProperties.Mock(0, 0),
        List.of());
  }
}
