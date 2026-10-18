package dev.resilient.gateway.ratelimit;

import dev.resilient.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class RedisTokenBucket {
  private static final String SCRIPT =
      """
      local key = KEYS[1]
      local capacity = tonumber(ARGV[1])
      local refill = tonumber(ARGV[2])
      local now = tonumber(ARGV[3])
      local requested = tonumber(ARGV[4])
      local state = redis.call('HMGET', key, 'tokens', 'ts')
      local tokens = tonumber(state[1]) or capacity
      local ts = tonumber(state[2]) or now
      tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill / 1000)
      local allowed = 0
      local retry = 0
      if tokens >= requested then
        allowed = 1
        tokens = tokens - requested
      else
        retry = math.ceil((requested - tokens) * 1000 / refill)
      end
      redis.call('HSET', key, 'tokens', tokens, 'ts', now)
      redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / refill) + 1000)
      return {allowed, retry}
      """;

  private final StringRedisTemplate redis;
  private final GatewayProperties.RateLimit config;

  public RedisTokenBucket(StringRedisTemplate redis, GatewayProperties settings) {
    this.redis = redis;
    this.config = settings.rateLimit();
  }

  public Decision take(String apiKey) {
    String key = "ratelimit:" + sha256(apiKey);
    Object raw =
        redis.execute(
            (RedisCallback<Object>)
                connection ->
                    connection.execute(
                        "EVAL",
                        bytes(SCRIPT),
                        bytes("1"),
                        bytes(key),
                        bytes(Long.toString(config.capacity())),
                        bytes(Long.toString(config.refillPerSecond())),
                        bytes(Long.toString(Instant.now().toEpochMilli())),
                        bytes("1")));
    if (!(raw instanceof List<?> result) || result.size() < 2) {
      throw new IllegalStateException("Redis returned an invalid token bucket result");
    }
    long allowed = ((Number) result.get(0)).longValue();
    long retryMs = ((Number) result.get(1)).longValue();
    return new Decision(allowed == 1, Math.max(1, (retryMs + 999) / 1000));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  public static String sha256(String value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes(value));
      return java.util.HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public record Decision(boolean allowed, long retryAfterSeconds) {}
}
