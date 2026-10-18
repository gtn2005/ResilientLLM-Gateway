package dev.resilient.gateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import dev.resilient.gateway.config.GatewayProperties;
import dev.resilient.gateway.observability.GatewayTelemetry;
import dev.resilient.gateway.provider.GroqClient;
import dev.resilient.gateway.provider.MockProvider;
import dev.resilient.gateway.provider.OpenAiCompatClient;
import dev.resilient.gateway.provider.ProviderClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class ProviderRouter {
  private final List<Slot> slots;
  private final MeterRegistry meters;
  private final GatewayTelemetry telemetry;
  private final ExecutorService calls = Executors.newFixedThreadPool(64);

  public ProviderRouter(
      GatewayProperties settings, MeterRegistry meters, GatewayTelemetry telemetry) {
    this.meters = meters;
    this.telemetry = telemetry;
    List<Slot> created = new ArrayList<>();
    for (GatewayProperties.Provider config : settings.providers()) {
      ProviderClient client =
          switch (config.type().toLowerCase()) {
            case "mock" -> new MockProvider(config, settings.requestTimeout());
            case "groq" -> new GroqClient(config, settings.requestTimeout());
            case "openai", "compatible" ->
                new OpenAiCompatClient(config, settings.requestTimeout());
            default ->
                throw new IllegalArgumentException("Unknown provider type: " + config.type());
          };
      CircuitBreaker breaker =
          CircuitBreaker.of(
              config.id(),
              CircuitBreakerConfig.custom()
                  .failureRateThreshold(50)
                  .slowCallRateThreshold(60)
                  .slowCallDurationThreshold(Duration.ofMillis(1400))
                  .minimumNumberOfCalls(5)
                  .slidingWindowSize(10)
                  .waitDurationInOpenState(Duration.ofSeconds(12))
                  .permittedNumberOfCallsInHalfOpenState(2)
                  .automaticTransitionFromOpenToHalfOpenEnabled(true)
                  .build());
      Retry retry =
          Retry.of(
              config.id(),
              RetryConfig.custom()
                  .maxAttempts(2)
                  .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(70, 2, 0.5))
                  .retryOnException(
                      error ->
                          !(error
                              instanceof
                              io.github.resilience4j.circuitbreaker.CallNotPermittedException))
                  .build());
      TimeLimiter limiter =
          TimeLimiter.of(
              config.id(),
              TimeLimiterConfig.custom()
                  .timeoutDuration(settings.requestTimeout())
                  .cancelRunningFuture(true)
                  .build());
      RateLimiter localLimiter =
          RateLimiter.of(
              config.id(),
              RateLimiterConfig.custom()
                  .limitForPeriod(10000)
                  .limitRefreshPeriod(Duration.ofSeconds(1))
                  .timeoutDuration(Duration.ZERO)
                  .build());
      Slot slot = new Slot(client, breaker, retry, limiter, localLimiter);
      Gauge.builder(
              "gateway_circuit_open",
              breaker,
              value -> value.getState() == CircuitBreaker.State.OPEN ? 1 : 0)
          .tag("provider", config.id())
          .register(meters);
      Gauge.builder(
              "gateway_circuit_state",
              breaker,
              value ->
                  switch (value.getState()) {
                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    case OPEN -> 2;
                    default -> 3;
                  })
          .tag("provider", config.id())
          .register(meters);
      created.add(slot);
    }
    this.slots = List.copyOf(created);
  }

  public RoutingResult route(JsonNode request) {
    int failedOver = 0;
    List<String> failures = new ArrayList<>();
    for (Slot slot : slots) {
      String id = slot.client.config().id();
      if (slot.breaker.getState() == CircuitBreaker.State.OPEN) {
        failures.add(id + ": circuit open");
        failedOver++;
        continue;
      }
      long started = System.nanoTime();
      try {
        JsonNode response =
            slot.retry.executeSupplier(
                () ->
                    slot.breaker.executeSupplier(
                        () ->
                            slot.localLimiter.executeSupplier(
                                () -> callWithTimeout(slot, request))));
        if (response == null) {
          throw new IllegalStateException("Provider returned an empty response");
        }
        recordProvider(id, "success", started);
        if (failedOver > 0) {
          Counter.builder("gateway_failover_total").register(meters).increment();
        }
        return new RoutingResult(response, id, failedOver);
      } catch (Exception failure) {
        recordProvider(id, "error", started);
        failures.add(id + ": " + failure.getClass().getSimpleName());
        failedOver++;
      }
    }
    throw new ProvidersUnavailableException(failures);
  }

  private void recordProvider(String id, String outcome, long started) {
    Timer.builder("gateway_provider_duration")
        .publishPercentileHistogram()
        .tag("provider", id)
        .tag("outcome", outcome)
        .register(meters)
        .record(Duration.ofNanos(System.nanoTime() - started));
  }

  private JsonNode callProvider(Slot slot, JsonNode request) {
    telemetry.providerCall();
    String id = slot.client.config().id();
    try {
      JsonNode response = slot.client.complete(request);
      Counter.builder("gateway_provider_calls_total")
          .tag("provider", id)
          .tag("outcome", "success")
          .register(meters)
          .increment();
      return response;
    } catch (RuntimeException failure) {
      Counter.builder("gateway_provider_calls_total")
          .tag("provider", id)
          .tag("outcome", "error")
          .register(meters)
          .increment();
      throw failure;
    }
  }

  private JsonNode callWithTimeout(Slot slot, JsonNode request) {
    try {
      return slot.limiter.executeFutureSupplier(
          () -> CompletableFuture.supplyAsync(() -> callProvider(slot, request), calls));
    } catch (Exception failure) {
      throw new IllegalStateException("Provider call timed out or failed", failure);
    }
  }

  public List<Map<String, Object>> status() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Slot slot : slots) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", slot.client.config().id());
      item.put("type", slot.client.config().type());
      item.put("model", slot.client.config().model());
      item.put("circuit", slot.breaker.getState().name());
      item.put("failureRate", slot.breaker.getMetrics().getFailureRate());
      result.add(item);
    }
    return result;
  }

  public CircuitBreaker breaker(String id) {
    return slots.stream()
        .filter(s -> s.client.config().id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id))
        .breaker;
  }

  public record RoutingResult(JsonNode response, String provider, int failovers) {}

  public static final class ProvidersUnavailableException extends RuntimeException {
    public ProvidersUnavailableException(List<String> failures) {
      super(String.join(", ", failures));
    }
  }

  private record Slot(
      ProviderClient client,
      CircuitBreaker breaker,
      Retry retry,
      TimeLimiter limiter,
      RateLimiter localLimiter) {}

  @PreDestroy
  public void close() {
    calls.shutdownNow();
  }
}
