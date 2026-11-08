# CitySeek Seckill Load Test Result

- Executed at: 2026-11-05T20:53:21.116865
- Runtime: Spring Boot random HTTP port + Testcontainers MySQL 8.0.36 + Redis 7.2
- Result: **FAIL**

| Metric | Value |
| --- | ---: |
| Requests | 500 |
| Concurrency | 100 |
| Initial stock | 200 |
| Connect timeout (ms) | 5000 |
| Request timeout (ms) | 10000 |
| Wall time (s) | 2.10 |
| Throughput (requests/s) | 237.70 |
| Average latency (ms) | 396.03 |
| P50 latency (ms) | 51.77 |
| P95 latency (ms) | 1786.07 |
| P99 latency (ms) | 1787.46 |
| Business success rate (%) | 40.00 |
| Transport success rate (%) | 100.00 |
| Accepted | 200 |
| Rejected | 300 |
| Transport errors | 0 |
| MySQL orders | 1 |
| MySQL remaining stock | 199 |
| Redis remaining stock | 0 |
| SUCCESS status records | 2 |

Consistency passes only when accepted requests, persisted orders, status records, and both stock values agree exactly.

## Phase measurements and diagnostics

Completion is first observed by a background poller (100 ms sleep between polls). Latencies start immediately before each HTTP send; they exclude client executor queueing. Database/SUCCESS observations are upper bounds, include query time, and add measurement load.

| Metric | Value |
| --- | ---: |
| Database drain within configured timeout (ms: 0) | false |
| SUCCESS drain within subsequent timeout (ms: 0) | false |
| Observed database completions | 0 |
| Observed SUCCESS completions | 0 |
| Post-HTTP SUCCESS drain (ms) | N/A (not fully observed) |
| Request to DB visibility P50 (ms) | N/A |
| Request to DB visibility P95 (ms) | N/A |
| Request to DB visibility P99 (ms) | N/A |
| Request to SUCCESS visibility P50 (ms) | N/A |
| Request to SUCCESS visibility P95 (ms) | N/A |
| Request to SUCCESS visibility P99 (ms) | N/A |
| Observer error | none |
| Consumer processing samples | 6 |
| Consumer processing mean (ms) | 148.30321666666666 |
| Consumer processing max (ms) | 726.0766 |
| cityseek.order.retry | 0.0 |
| cityseek.order.compensated | 0.0 |
| cityseek.order.dlq | 0.0 |
| Final Pending | 1 |
| Final consumer lag | 193 |

Completion percentiles describe observed orders only; check counts and drain flags before interpreting them. Consumer processing includes lock, DB transaction, status update and ACK, but excludes queue wait. Final Pending and lag are end snapshots, not peak backlog measurements.

Request error categories: {}
