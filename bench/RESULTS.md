# Benchmark results

No measured result is recorded yet. This environment does not have Docker or k6, so publishing throughput, latency, cache hit rate, provider call reduction, or a zero-error chaos claim would be fabricated.

| Measurement | Result |
| --- | --- |
| Hardware | Not measured |
| Sustained requests per second | Not measured |
| p95 client latency | Not measured |
| Cache hit rate | Not measured |
| Provider call reduction | Not measured |
| Chaos client-visible errors | Not measured |

Dataset: `bench/dataset.jsonl` has 300 prompts: 180 base prompts and 120 paraphrases, so 40% of rows are paraphrases. Twelve infrastructure subjects were combined with fifteen base formulations and ten paraphrase formulations. The load script cycles the dataset, so repeated exact prompts will also affect cache hit rate. Tests target MockProvider only.

Reproduce from the repository root after `docker compose up --build -d`:

```sh
k6 run bench/load.js
k6 run bench/chaos.js
```

The five minute load run defaults to 30 requests per second. The chaos run sends uncached requests for 100 seconds, disables primary at 30 seconds, disables secondary at 60 seconds, and restores both during teardown. Capture the k6 summary, the gateway's `/admin/status` telemetry, and Prometheus counters before writing numbers here. Use `gateway_provider_calls_total` to count every provider attempt, including retries.
