package dev.resilient.gateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.resilient.gateway.config.GatewayProperties;
import dev.resilient.gateway.ratelimit.RedisTokenBucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class SemanticCache {
  private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);
  private static final String INDEX = "idx:llmcache";
  private final StringRedisTemplate redis;
  private final EmbeddingService embeddings;
  private final GatewayProperties.Cache config;
  private final ObjectMapper mapper;
  private final MeterRegistry meters;
  private final Deque<Map<String, Object>> audits = new ArrayDeque<>();
  private volatile boolean indexReady;

  public SemanticCache(
      StringRedisTemplate redis,
      EmbeddingService embeddings,
      GatewayProperties settings,
      ObjectMapper mapper,
      MeterRegistry meters) {
    this.redis = redis;
    this.embeddings = embeddings;
    this.config = settings.cache();
    this.mapper = mapper;
    this.meters = meters;
  }

  public Prepared prepare(String tenant, String model, double temperature, String prompt) {
    if (!eligible(temperature) || !embeddings.ready()) {
      return null;
    }
    try {
      ensureIndex();
      return new Prepared(namespace(tenant, model, temperature), embeddings.embed(prompt));
    } catch (Exception failure) {
      log.warn("Cache preparation failed, routing to provider: {}", failure.toString());
      return null;
    }
  }

  public Optional<Hit> lookup(Prepared prepared, String prompt) {
    try {
      Object raw =
          redis.execute(
              (RedisCallback<Object>)
                  connection ->
                      connection.execute(
                          "FT.SEARCH",
                          bytes(INDEX),
                          bytes(
                              "(@namespace:{"
                                  + prepared.namespace()
                                  + "})=>[KNN 1 @vector $vec AS distance]"),
                          bytes("PARAMS"),
                          bytes("2"),
                          bytes("vec"),
                          vectorBytes(prepared.vector()),
                          bytes("SORTBY"),
                          bytes("distance"),
                          bytes("RETURN"),
                          bytes("3"),
                          bytes("response"),
                          bytes("provider"),
                          bytes("distance"),
                          bytes("DIALECT"),
                          bytes("2")));
      if (!(raw instanceof List<?> list) || list.size() < 3) {
        miss();
        return Optional.empty();
      }
      Map<String, String> fields = parseFields(list.get(2));
      double similarity = 1 - Double.parseDouble(fields.getOrDefault("distance", "1"));
      if (similarity < config.threshold()) {
        audit("rejected", similarity, prompt);
        miss();
        return Optional.empty();
      }
      JsonNode response = mapper.readTree(fields.get("response"));
      hit();
      audit("hit", similarity, prompt);
      return Optional.of(new Hit(response, fields.getOrDefault("provider", "unknown"), similarity));
    } catch (Exception failure) {
      if (failure.toString().contains("Unknown Index name")) {
        indexReady = false;
      }
      log.warn("Cache lookup failed, routing to provider: {}", failure.toString());
      miss();
      return Optional.empty();
    }
  }

  public void store(Prepared prepared, JsonNode response, String provider) {
    try {
      ensureIndex();
      String key = "llmcache:" + UUID.randomUUID();
      byte[] vector = vectorBytes(prepared.vector());
      redis.execute(
          (RedisCallback<Object>)
              connection -> {
                connection.execute(
                    "HSET",
                    bytes(key),
                    bytes("namespace"),
                    bytes(prepared.namespace()),
                    bytes("vector"),
                    vector,
                    bytes("response"),
                    bytes(response.toString()),
                    bytes("provider"),
                    bytes(provider));
                connection.execute(
                    "EXPIRE", bytes(key), bytes(Long.toString(config.ttl().toSeconds())));
                return null;
              });
    } catch (Exception failure) {
      if (failure.toString().contains("Unknown Index name")) {
        indexReady = false;
      }
      log.warn("Cache write failed: {}", failure.toString());
    }
  }

  public boolean eligible(double temperature) {
    return config.enabled() && temperature <= 0.3;
  }

  public boolean ready() {
    if (!config.enabled() || !embeddings.ready()) {
      return false;
    }
    try {
      ensureIndex();
      return true;
    } catch (Exception failure) {
      return false;
    }
  }

  public double threshold() {
    return config.threshold();
  }

  public synchronized List<Map<String, Object>> auditTrail() {
    return List.copyOf(audits);
  }

  private synchronized void ensureIndex() {
    if (indexReady) {
      return;
    }
    try {
      redis.execute(
          (RedisCallback<Object>)
              connection ->
                  connection.execute(
                      "FT.CREATE",
                      bytes(INDEX),
                      bytes("ON"),
                      bytes("HASH"),
                      bytes("PREFIX"),
                      bytes("1"),
                      bytes("llmcache:"),
                      bytes("SCHEMA"),
                      bytes("namespace"),
                      bytes("TAG"),
                      bytes("vector"),
                      bytes("VECTOR"),
                      bytes("HNSW"),
                      bytes("6"),
                      bytes("TYPE"),
                      bytes("FLOAT32"),
                      bytes("DIM"),
                      bytes("384"),
                      bytes("DISTANCE_METRIC"),
                      bytes("COSINE")));
    } catch (Exception failure) {
      if (!failure.toString().contains("Index already exists")) {
        throw failure;
      }
    }
    indexReady = true;
  }

  private Map<String, String> parseFields(Object raw) {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (raw instanceof List<?> fields) {
      for (int i = 0; i + 1 < fields.size(); i += 2) {
        parsed.put(asString(fields.get(i)), asString(fields.get(i + 1)));
      }
    }
    return parsed;
  }

  private String asString(Object raw) {
    if (raw instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return String.valueOf(raw);
  }

  private String namespace(String tenant, String model, double temperature) {
    return RedisTokenBucket.sha256(tenant + "|" + model + "|" + temperature);
  }

  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] vectorBytes(float[] vector) {
    ByteBuffer buffer =
        ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (float component : vector) {
      buffer.putFloat(component);
    }
    return buffer.array();
  }

  private void hit() {
    Counter.builder("gateway_cache_total").tag("result", "hit").register(meters).increment();
  }

  private void miss() {
    Counter.builder("gateway_cache_total").tag("result", "miss").register(meters).increment();
  }

  private void audit(String decision, double similarity, String prompt) {
    if (ThreadLocalRandom.current().nextDouble() > config.auditSampleRate()) {
      return;
    }
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("time", Instant.now().toString());
    item.put("decision", decision);
    item.put("similarity", Math.round(similarity * 1000) / 1000.0);
    item.put("promptHash", RedisTokenBucket.sha256(prompt).substring(0, 12));
    synchronized (this) {
      audits.addFirst(item);
      while (audits.size() > 50) {
        audits.removeLast();
      }
    }
  }

  public record Hit(JsonNode response, String provider, double similarity) {}

  public record Prepared(String namespace, float[] vector) {}
}
