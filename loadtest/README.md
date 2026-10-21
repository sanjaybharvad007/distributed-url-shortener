# Load testing

`k6/redirect.js` drives the redirect path at a constant arrival rate with a hot/cold key mix
(80 % of traffic to 20 % of links) plus a trickle of unknown codes, and fails if the latency or
error SLOs are breached.

```bash
make up
make load-test                                   # 1,000 rps for 2 minutes against the local stack
k6 run -e BASE_URL=https://sho.rt -e API_URL=https://sho.rt -e API_KEY=$KEY -e RATE=5000 -e DURATION=10m k6/redirect.js
```

The script enforces the SLO budget from `docs/ARCHITECTURE.md` as k6 thresholds, so a run exits
non-zero when the system is out of budget:

| Metric | Threshold |
| --- | --- |
| Redirect p95 | < 20 ms |
| Redirect p99 | < 50 ms |
| Failed requests | < 0.1 % |
| Check pass rate | > 99.9 % |

Record your own measurements per environment in the table below after each significant change
(cold-cache p99 is dominated by the DynamoDB round-trip; the `redirects_over_20ms` counter
approximates the miss rate during warm-up).

| Date | Environment | Rate | p50 | p95 | p99 | Errors |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |
