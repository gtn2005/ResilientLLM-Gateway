package dev.resilient.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.resilient.gateway.config.GatewayProperties;
import dev.resilient.gateway.observability.GatewayTelemetry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderRouterTest {
  private HttpServer primary;
  private HttpServer secondary;
  private ProviderRouter router;
  private final AtomicBoolean primaryFails = new AtomicBoolean(true);
  private final AtomicInteger primaryCalls = new AtomicInteger();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void startServers() throws IOException {
    primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    primary.createContext(
        "/v1/chat/completions",
        exchange -> {
          primaryCalls.incrementAndGet();
          int code = primaryFails.get() ? 503 : 200;
          byte[] body =
              (primaryFails.get() ? "{\"error\":\"down\"}" : "{\"source\":\"primary\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(code, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    secondary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    secondary.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body = "{\"source\":\"secondary\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    primary.start();
    secondary.start();
    GatewayProperties settings =
        new GatewayProperties(
            "gateway",
            "test",
            Duration.ofMillis(600),
            new GatewayProperties.RateLimit(10, 1),
            new GatewayProperties.Cache(false, 0.92, Duration.ofHours(1), 0.05, "/missing"),
            new GatewayProperties.Mock(0, 0),
            List.of(
                new GatewayProperties.Provider("primary", "mock", url(primary), "mock-1", ""),
                new GatewayProperties.Provider("secondary", "mock", url(secondary), "mock-1", "")));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    router = new ProviderRouter(settings, registry, new GatewayTelemetry(registry));
  }

  @AfterEach
  void close() {
    router.close();
    primary.stop(0);
    secondary.stop(0);
  }

  @Test
  void failsOverAndSkipsOpenCircuitThenRecovers() throws Exception {
    JsonNode request =
        mapper.readTree(
            "{\"model\":\"mock-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
    for (int i = 0; i < 4; i++) {
      assertThat(router.route(request).provider()).isEqualTo("secondary");
    }
    assertThat(router.breaker("primary").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    int callsAtOpen = primaryCalls.get();
    assertThat(router.route(request).provider()).isEqualTo("secondary");
    assertThat(primaryCalls.get()).isEqualTo(callsAtOpen);

    primaryFails.set(false);
    router.breaker("primary").transitionToHalfOpenState();
    assertThat(router.route(request).provider()).isEqualTo("primary");
    assertThat(router.route(request).provider()).isEqualTo("primary");
    assertThat(router.breaker("primary").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  private String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
}
