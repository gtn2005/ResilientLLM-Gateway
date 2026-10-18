package dev.resilient.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.resilient.gateway.config.GatewayProperties;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpProviderClient implements ProviderClient {
  private final GatewayProperties.Provider config;
  private final RestClient http;

  public HttpProviderClient(GatewayProperties.Provider config, Duration timeout) {
    this.config = config;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    this.http =
        RestClient.builder().baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
  }

  @Override
  public GatewayProperties.Provider config() {
    return config;
  }

  @Override
  public JsonNode complete(JsonNode request) {
    JsonNode forwarded = request.deepCopy();
    if (forwarded instanceof ObjectNode object
        && config.model() != null
        && !config.model().isBlank()) {
      object.put("model", config.model());
    }
    return http.post()
        .uri("/v1/chat/completions")
        .headers(
            headers -> {
              if (config.apiKey() != null && !config.apiKey().isBlank()) {
                headers.setBearerAuth(config.apiKey());
              }
            })
        .body(forwarded)
        .retrieve()
        .body(JsonNode.class);
  }
}
