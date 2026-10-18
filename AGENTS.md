# AGENTS.md: ResilientLLM Gateway

Fault-tolerant gateway in front of multiple LLM providers. OpenAI-compatible API, circuit breakers, rate limiting, provider failover, Redis semantic cache, full observability, load and chaos tested.

## Goal and resume claims (every claim must be true and measurable)
- Circuit breakers, token-bucket rate limiting, provider failover, Redis semantic cache.
- k6 load test: sustained req/s, p95 latency, cache hit rate, % fewer provider calls.
- Zero dropped requests during injected provider outages.
- Prometheus + Grafana dashboards (latency, error rate, circuit state). Docker Compose deployment.
After Milestone 7, write measured numbers into `bench/RESULTS.md` and update the resume (search `APPROX` in the .tex).

## Stack
- Java 17, Spring Boot 3 (Spring MVC with virtual threads, or WebFlux; pick one and stay consistent), Maven
- Resilience4j: CircuitBreaker, RateLimiter, Retry, TimeLimiter
- Redis Stack (RediSearch vector similarity) for semantic cache; plain Redis keys for rate limit counters
- Embeddings: local ONNX MiniLM via DJL (no external API cost); abstract behind `EmbeddingService`
- Providers: Groq (OpenAI-compatible), one more OpenAI-compatible provider, and a **MockProvider** with configurable latency and error rate (needed for repeatable chaos tests)
- Micrometer -> Prometheus -> Grafana
- k6 for load and chaos, Testcontainers for integration tests, Docker Compose

## Repo layout
```
resilient-llm-gateway/
  src/main/java/.../
    api/            # POST /v1/chat/completions (OpenAI-compatible), /admin endpoints
    provider/       # ProviderClient interface, GroqClient, OpenAiCompatClient, MockProvider
    routing/        # ProviderRouter: priority, health, failover
    resilience/     # breaker/ratelimiter config per provider
    cache/          # SemanticCache, EmbeddingService, cache policy
    ratelimit/      # per-API-key token bucket (Redis)
    observability/  # metrics, request tracing (request id, provider, cache hit, latency)
  bench/            # k6 scripts, prompt dataset, RESULTS.md
  grafana/          # provisioned dashboards JSON
  docker-compose.yml, prometheus.yml
  AGENTS.md
```

## Commands (keep working at all times)
- `docker compose up --build` starts gateway, redis-stack, prometheus, grafana, mock providers
- `./mvnw verify` runs unit + Testcontainers integration tests
- `k6 run bench/load.js` and `k6 run bench/chaos.js`
- `./mvnw -q spotless:check` before commit

## Build order (one milestone per PR, each ends green)
1. **Skeleton.** `/v1/chat/completions` proxying to MockProvider, request id, structured logging, `/actuator/prometheus`.
2. **Providers + routing.** `ProviderClient` interface, Groq and second provider, ordered failover (primary, secondary, tertiary). Streaming support only if time allows; otherwise document as non-goal.
3. **Resilience.** Per-provider CircuitBreaker (failure rate and slow-call thresholds), Retry with jittered backoff, TimeLimiter. Router skips OPEN breakers and fails over. Unit tests for each state transition.
4. **Rate limiting.** Per-API-key token bucket in Redis (atomic Lua script). Return 429 with `Retry-After`. Test under concurrency.
5. **Semantic cache.** Embed prompt, KNN search in Redis, return cached response above similarity threshold (default 0.92, configurable), TTL, bypass header `X-Cache-Bypass`. Key includes model and temperature; skip cache when temperature > 0.3. Log false-positive risk: expose threshold and a sampled audit endpoint.
6. **Observability.** Metrics: request count/latency histogram by provider, cache hit/miss, breaker state gauge, failover count, provider calls total. Provisioned Grafana dashboard with panels for p50/p95/p99, error rate, circuit state, cache hit rate.
7. **Bench.** `bench/dataset.jsonl` with 300+ prompts where ~40% are paraphrases of others (document how generated). `load.js`: ramp to target req/s for 5 min against MockProvider with realistic latency. `chaos.js`: kill primary provider mid-run (MockProvider admin toggle) and then secondary; assert zero failed client requests. Record: sustained req/s, p95, hit rate, `1 - provider_calls/total_requests`.
8. **Polish.** README with architecture diagram, Grafana screenshot, RESULTS table, limitations.

## Conventions
- No secrets in repo; `.env.example` only. API keys for real providers are optional; everything must run on MockProvider.
- All config in `application.yml` with sane defaults; document each knob in README.
- Never block on provider calls without a timeout.
- Tests: breaker transitions, failover order, rate limit under concurrency, cache hit/miss/bypass, one Testcontainers end-to-end.
- Do not use em dashes or en dashes anywhere (code comments, docs, resume text).
- Small commits, conventional commit messages.

## Acceptance criteria
- Full stack up with one command; Grafana dashboard shows live data during k6 run.
- Chaos run: primary killed, then secondary killed, client-visible error count is 0 (or reported exactly if not, and fixed or disclosed).
- `bench/RESULTS.md` lists hardware used, dataset description, req/s, p95, hit rate, provider call reduction, chaos outcome, and the exact k6 command to reproduce.

## Honesty rules for the agent
- Never fabricate metrics. Numbers on the resume must come from `bench/RESULTS.md`.
- Cache hit rate depends on the dataset; state the paraphrase ratio next to it.
- State that load tests ran against MockProvider (and any real-provider runs separately).
