package dev.resilient.gateway.api;

import dev.resilient.gateway.cache.SemanticCache;
import dev.resilient.gateway.config.GatewayProperties;
import dev.resilient.gateway.observability.GatewayTelemetry;
import dev.resilient.gateway.routing.ProviderRouter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class AdminController {
  private final GatewayProperties settings;
  private final ProviderRouter router;
  private final SemanticCache cache;
  private final GatewayTelemetry telemetry;
  private final RestClient http;

  public AdminController(
      GatewayProperties settings,
      ProviderRouter router,
      SemanticCache cache,
      GatewayTelemetry telemetry) {
    this.settings = settings;
    this.router = router;
    this.cache = cache;
    this.telemetry = telemetry;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(350));
    factory.setReadTimeout(Duration.ofMillis(350));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @GetMapping("/status")
  public Map<String, Object> status(
      @RequestHeader(value = "X-Admin-Token", required = false) String token) {
    authenticate(token);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("mode", "gateway");
    List<Map<String, Object>> providers = router.status();
    for (Map<String, Object> item : providers) {
      GatewayProperties.Provider provider =
          settings.providers().stream()
              .filter(candidate -> candidate.id().equals(item.get("id")))
              .findFirst()
              .orElseThrow();
      if ("mock".equals(provider.type())) {
        try {
          Map<?, ?> remote =
              http.get().uri(provider.baseUrl() + "/admin/state").retrieve().body(Map.class);
          item.put("enabled", Boolean.TRUE.equals(remote.get("enabled")));
        } catch (Exception failure) {
          item.put("enabled", false);
        }
      }
    }
    result.put("providers", providers);
    result.put(
        "cache",
        Map.of(
            "ready",
            cache.ready(),
            "enabled",
            settings.cache().enabled(),
            "threshold",
            cache.threshold()));
    result.put("telemetry", telemetry.snapshot());
    return result;
  }

  @GetMapping("/cache/audit")
  public List<Map<String, Object>> audit(
      @RequestHeader(value = "X-Admin-Token", required = false) String token) {
    authenticate(token);
    return cache.auditTrail();
  }

  @PostMapping("/providers/{id}/state")
  public Map<String, Object> providerState(
      @PathVariable String id,
      @RequestBody Map<String, Boolean> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token) {
    authenticate(token);
    GatewayProperties.Provider provider =
        settings.providers().stream()
            .filter(p -> p.id().equals(id))
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown provider"));
    if (!"mock".equals(provider.type())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Only mock providers support injected outages");
    }
    return http.post()
        .uri(provider.baseUrl() + "/admin/state")
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  private void authenticate(String token) {
    String expected = settings.adminToken();
    if (token == null
        || expected == null
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
    }
  }
}
