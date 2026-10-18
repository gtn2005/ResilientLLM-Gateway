package dev.resilient.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class GatewayTelemetry {
  private final MeterRegistry meters;
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong providerCalls = new AtomicLong();
  private final AtomicLong failovers = new AtomicLong();
  private final Deque<Sample> samples = new ArrayDeque<>();
  private final Deque<Map<String, Object>> events = new ArrayDeque<>();

  public GatewayTelemetry(MeterRegistry meters) {
    this.meters = meters;
  }

  public void providerCall() {
    providerCalls.incrementAndGet();
  }

  public void record(
      String provider,
      String cache,
      int status,
      int failoverCount,
      long startedNanos,
      String requestId) {
    long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    requests.incrementAndGet();
    if (status >= 500) {
      errors.incrementAndGet();
    }
    if ("hit".equals(cache)) {
      cacheHits.incrementAndGet();
    } else if ("miss".equals(cache)) {
      cacheMisses.incrementAndGet();
    }
    if (failoverCount > 0) {
      failovers.incrementAndGet();
    }
    Counter.builder("gateway_requests_total")
        .tag("provider", provider)
        .tag("cache", cache)
        .tag("status", Integer.toString(status))
        .register(meters)
        .increment();
    Timer.builder("gateway_request_duration")
        .publishPercentileHistogram()
        .tag("provider", provider)
        .register(meters)
        .record(Duration.ofMillis(durationMs));
    long now = Instant.now().toEpochMilli();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("time", Instant.now().toString());
    event.put("provider", provider);
    event.put("cache", cache);
    event.put("status", status);
    event.put("latencyMs", durationMs);
    event.put("requestId", requestId);
    synchronized (this) {
      samples.addLast(new Sample(now, durationMs, status));
      events.addFirst(event);
      while (samples.size() > 2000
          || (!samples.isEmpty() && samples.peekFirst().time < now - 60000)) {
        samples.removeFirst();
      }
      while (events.size() > 12) {
        events.removeLast();
      }
    }
  }

  public synchronized Map<String, Object> snapshot() {
    long now = Instant.now().toEpochMilli();
    List<Long> latencies = new ArrayList<>();
    long lastFive = 0;
    List<Map<String, Object>> series = new ArrayList<>();
    for (int offset = 29; offset >= 0; offset--) {
      long second = now / 1000 - offset;
      long count = 0;
      for (Sample sample : samples) {
        if (sample.time / 1000 == second) {
          count++;
        }
      }
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("time", second);
      point.put("value", count);
      series.add(point);
    }
    for (Sample sample : samples) {
      latencies.add(sample.latencyMs);
      if (sample.time >= now - 5000) {
        lastFive++;
      }
    }
    latencies.sort(Comparator.naturalOrder());
    long p95 =
        latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requests", requests.get());
    result.put("errors", errors.get());
    result.put("cacheHits", cacheHits.get());
    result.put("cacheMisses", cacheMisses.get());
    result.put("providerCalls", providerCalls.get());
    result.put("failovers", failovers.get());
    result.put("rps", Math.round(lastFive / 5.0 * 10) / 10.0);
    result.put("p95Ms", p95);
    result.put(
        "cacheHitRate",
        cacheHits.get() + cacheMisses.get() == 0
            ? 0
            : Math.round(cacheHits.get() * 1000.0 / (cacheHits.get() + cacheMisses.get())) / 10.0);
    result.put(
        "reduction",
        requests.get() == 0
            ? 0
            : Math.round((1 - providerCalls.get() * 1.0 / requests.get()) * 1000) / 10.0);
    result.put("series", series);
    result.put("events", List.copyOf(events));
    return result;
  }

  private record Sample(long time, long latencyMs, int status) {}
}
