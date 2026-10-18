package dev.resilient.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.resilient.gateway.config.GatewayProperties;

public interface ProviderClient {
  GatewayProperties.Provider config();

  JsonNode complete(JsonNode request);
}
