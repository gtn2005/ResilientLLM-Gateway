package dev.resilient.gateway.cache;

public interface EmbeddingService {
  float[] embed(String text);

  boolean ready();
}
