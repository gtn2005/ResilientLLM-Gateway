package dev.resilient.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.resilient.gateway.cache.SemanticCache;
import dev.resilient.gateway.observability.GatewayTelemetry;
import dev.resilient.gateway.ratelimit.RedisTokenBucket;
import dev.resilient.gateway.routing.ProviderRouter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatControllerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void bypassRoutesWithoutReadingOrWritingCache() throws Exception {
    RedisTokenBucket limiter = org.mockito.Mockito.mock(RedisTokenBucket.class);
    SemanticCache cache = org.mockito.Mockito.mock(SemanticCache.class);
    ProviderRouter router = org.mockito.Mockito.mock(ProviderRouter.class);
    GatewayTelemetry telemetry = org.mockito.Mockito.mock(GatewayTelemetry.class);
    when(limiter.take("test-key")).thenReturn(new RedisTokenBucket.Decision(true, 1));
    JsonNode response = mapper.readTree("{\"choices\":[]}");
    when(router.route(any())).thenReturn(new ProviderRouter.RoutingResult(response, "primary", 0));
    JsonNode request =
        mapper.readTree(
            "{\"model\":\"mock-1\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
    MockHttpServletResponse servlet = new MockHttpServletResponse();

    ResponseEntity<?> result =
        new ChatController(limiter, cache, router, telemetry)
            .complete(request, "Bearer test-key", true, servlet);

    assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(servlet.getHeader("X-Cache")).isEqualTo("BYPASS");
    verify(cache, never()).prepare(anyString(), anyString(), anyDouble(), anyString());
    verify(cache, never()).store(any(), any(), anyString());
  }

  @Test
  void returnsCachedResponseWithoutCallingProvider() throws Exception {
    RedisTokenBucket limiter = org.mockito.Mockito.mock(RedisTokenBucket.class);
    SemanticCache cache = org.mockito.Mockito.mock(SemanticCache.class);
    ProviderRouter router = org.mockito.Mockito.mock(ProviderRouter.class);
    GatewayTelemetry telemetry = org.mockito.Mockito.mock(GatewayTelemetry.class);
    when(limiter.take("test-key")).thenReturn(new RedisTokenBucket.Decision(true, 1));
    JsonNode response = mapper.readTree("{\"choices\":[]}");
    when(cache.prepare(anyString(), anyString(), anyDouble(), anyString()))
        .thenReturn(new SemanticCache.Prepared("tenant", new float[384]));
    when(cache.lookup(any(), anyString()))
        .thenReturn(Optional.of(new SemanticCache.Hit(response, "primary", 0.99)));
    JsonNode request =
        mapper.readTree(
            "{\"model\":\"mock-1\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
    MockHttpServletResponse servlet = new MockHttpServletResponse();

    ResponseEntity<?> result =
        new ChatController(limiter, cache, router, telemetry)
            .complete(request, "Bearer test-key", false, servlet);

    assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(servlet.getHeader("X-Cache")).isEqualTo("HIT");
    verify(router, never()).route(any());
  }
}
