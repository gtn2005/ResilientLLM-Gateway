package dev.resilient.gateway.provider;

import dev.resilient.gateway.config.GatewayProperties;
import java.time.Duration;

public final class GroqClient extends HttpProviderClient {
  public GroqClient(GatewayProperties.Provider config, Duration timeout) {
    super(config, timeout);
  }
}
