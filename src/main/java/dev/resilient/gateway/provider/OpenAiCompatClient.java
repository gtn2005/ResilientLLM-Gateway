package dev.resilient.gateway.provider;

import dev.resilient.gateway.config.GatewayProperties;
import java.time.Duration;

public final class OpenAiCompatClient extends HttpProviderClient {
  public OpenAiCompatClient(GatewayProperties.Provider config, Duration timeout) {
    super(config, timeout);
  }
}
