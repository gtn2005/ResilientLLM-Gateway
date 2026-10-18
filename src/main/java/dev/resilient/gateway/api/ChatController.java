package dev.resilient.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.resilient.gateway.cache.SemanticCache;
import dev.resilient.gateway.observability.GatewayTelemetry;
import dev.resilient.gateway.ratelimit.RedisTokenBucket;
import dev.resilient.gateway.routing.ProviderRouter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class ChatController {
  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private final RedisTokenBucket rateLimit;
  private final SemanticCache cache;
  private final ProviderRouter router;
  private final GatewayTelemetry telemetry;

  public ChatController(
      RedisTokenBucket rateLimit,
      SemanticCache cache,
      ProviderRouter router,
      GatewayTelemetry telemetry) {
    this.rateLimit = rateLimit;
    this.cache = cache;
    this.router = router;
    this.telemetry = telemetry;
  }

  @PostMapping("/v1/chat/completions")
  public ResponseEntity<?> complete(
      @RequestBody JsonNode request,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Cache-Bypass", defaultValue = "false") boolean bypass,
      HttpServletResponse servletResponse) {
    long started = System.nanoTime();
    String requestId = MDC.get("requestId");
    String apiKey =
        authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring(7).trim()
            : null;
    if (apiKey == null || apiKey.isBlank()) {
      telemetry.record("none", "skip", 401, 0, started, requestId);
      return ResponseEntity.status(401)
          .body(
              Map.of(
                  "error",
                  Map.of("message", "Bearer API key required", "type", "authentication_error")));
    }
    if (!request.hasNonNull("model")
        || !request.path("messages").isArray()
        || request.path("messages").isEmpty()) {
      telemetry.record("none", "skip", 400, 0, started, requestId);
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  Map.of(
                      "message",
                      "model and nonempty messages are required",
                      "type",
                      "invalid_request_error")));
    }
    if (request.path("stream").asBoolean(false)) {
      telemetry.record("none", "skip", 400, 0, started, requestId);
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  Map.of(
                      "message", "streaming is not supported", "type", "invalid_request_error")));
    }
    RedisTokenBucket.Decision decision;
    try {
      decision = rateLimit.take(apiKey);
    } catch (Exception failure) {
      telemetry.record("none", "skip", 503, 0, started, requestId);
      return ResponseEntity.status(503)
          .body(
              Map.of(
                  "error",
                  Map.of("message", "Rate limiter unavailable", "type", "service_unavailable")));
    }
    if (!decision.allowed()) {
      telemetry.record("none", "skip", 429, 0, started, requestId);
      return ResponseEntity.status(429)
          .header("Retry-After", Long.toString(decision.retryAfterSeconds()))
          .body(
              Map.of(
                  "error", Map.of("message", "Rate limit exceeded", "type", "rate_limit_error")));
    }
    String model = request.path("model").asText();
    double temperature = request.path("temperature").asDouble(1.0);
    String prompt = request.path("messages").toString();
    SemanticCache.Prepared prepared =
        bypass ? null : cache.prepare(apiKey, model, temperature, prompt);
    if (prepared != null) {
      var cached = cache.lookup(prepared, prompt);
      if (cached.isPresent()) {
        var hit = cached.get();
        servletResponse.setHeader("X-Cache", "HIT");
        servletResponse.setHeader("X-Provider", hit.provider());
        telemetry.record(hit.provider(), "hit", 200, 0, started, requestId);
        log.info(
            "chat provider={} cache=hit latencyMs={}",
            hit.provider(),
            (System.nanoTime() - started) / 1_000_000);
        return ResponseEntity.ok(hit.response());
      }
    }
    try {
      ProviderRouter.RoutingResult result = router.route(request);
      if (prepared != null) {
        cache.store(prepared, result.response(), result.provider());
      }
      String cacheState = bypass ? "bypass" : prepared == null ? "skip" : "miss";
      servletResponse.setHeader("X-Cache", cacheState.toUpperCase());
      servletResponse.setHeader("X-Provider", result.provider());
      telemetry.record(result.provider(), cacheState, 200, result.failovers(), started, requestId);
      log.info(
          "chat provider={} cache={} failovers={} latencyMs={}",
          result.provider(),
          cacheState,
          result.failovers(),
          (System.nanoTime() - started) / 1_000_000);
      return ResponseEntity.ok(result.response());
    } catch (ProviderRouter.ProvidersUnavailableException failure) {
      telemetry.record("none", prepared == null ? "skip" : "miss", 503, 0, started, requestId);
      log.error("All providers unavailable: {}", failure.getMessage());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              Map.of(
                  "error",
                  Map.of("message", "All providers unavailable", "type", "provider_unavailable")));
    }
  }
}
