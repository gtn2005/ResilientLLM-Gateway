package dev.resilient.gateway.provider;

import dev.resilient.gateway.config.GatewayProperties;
import java.time.Duration;

public final class MockProvider extends HttpProviderClient {
  public MockProvider(GatewayProperties.Provider config, Duration timeout) {
    super(config, timeout);
  }
}
