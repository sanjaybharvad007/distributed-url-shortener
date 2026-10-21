# Runbook

On-call reference for the URL shortener. Dashboards: Grafana → *URL Shortener / Overview*.

## Service inventory

| Service | Port | Health | Metrics | Logs |
| --- | --- | --- | --- | --- |
| shortener-api (Java) | 8080 | `/actuator/health/{liveness,readiness}` | `/actuator/prometheus` | CloudWatch `/ecs/shortener-api` |
| redirector (Go) | 8081 | `/healthz`, `/readyz` | `/metrics` | CloudWatch `/ecs/redirector` |

## Alerts and what to do

### `HighErrorRateBurn` (SLO burn rate)

The availability SLO (99.9 % of requests succeed) is burning too fast.

1. Open the Overview dashboard → *Errors by service / status*. Is it 5xx from one service or both?
2. Both services failing on cache misses → check `RedisShardDown`; failing on everything → check `DynamoDBThrottled`
   and the CloudWatch DynamoDB *SystemErrors* metric.
3. Only `shortener-api` → check the circuit breaker state (`resilience4j_circuitbreaker_state`). If open, DynamoDB is
   slow or unavailable; creates will 503 until it recovers. Redirects keep working from cache.
4. Roll back the last deploy if the alert started with a deployment (see *Rollback*).

### `RedisShardDown`

A shard is unreachable from at least one service.

- Keys on that shard miss the cache and fall through to DynamoDB; expect higher p99 and DynamoDB read spikes
  (on-demand mode absorbs it). Rate limiting for clients hashed to that shard fails open.
- ElastiCache: check the node in the console; failover to the replica is automatic for Multi-AZ replication groups.
- Do **not** change the node list under load unless the node is permanently gone — changing membership moves
  ~1/N of keys and causes a miss storm. If you must, roll both services with the new `*_REDIS_NODES` value in
  the same deployment so their rings stay identical.

### `CacheHitRatioLow`

Hit ratio under 80 % for 10 minutes.

- After a deploy that changed the node list → expected; it recovers as the cache warms.
- Sudden drop without a deploy → a shard was evicted/restarted (check `redis_up`), or memory pressure
  (`maxmemory-policy allkeys-lru` evicting hot keys — scale the node type).
- Steady decline → traffic pattern shift toward long-tail links; consider raising positive TTLs.

### `ClickStreamLagHigh`

The API's click consumer is behind by more than 10k events.

- Check `shortener-api` logs for DynamoDB write errors on `ADD click_count`.
- The stream is capped (`MAXLEN ~ 1,000,000`); if the lag keeps growing events will be trimmed and counts under-report.
- Scale the API service out; consumers in the same group share the stream.

### `RateLimitFailOpen`

Rate-limit decisions are failing open because Redis is unavailable → see `RedisShardDown`. The service is
unprotected from abusive clients until Redis recovers; consider enabling the ALB WAF rate rule temporarily.

### `HighLatencyP99`

- Redirector p99 > 50 ms: almost always cache misses (`cache_lookups_total{result="miss"}` rising). See above.
- API p99 > 300 ms: DynamoDB latency or GC pauses (`jvm_gc_pause_seconds`). ZGC is enabled; check heap sizing.

## Deployments

CD runs on every merge to `main`:

1. CI must be green (unit + integration tests, lint, Trivy scan).
2. Images are tagged with the git SHA and pushed to ECR.
3. New ECS task definitions are registered and services updated with `minimumHealthyPercent=100`,
   `maximumPercent=200`; the ALB only routes to tasks that pass readiness checks.
4. The deployment circuit breaker rolls back automatically if new tasks fail to become healthy.

### Rollback

```bash
# Find the previous task definition revision
aws ecs describe-services --cluster url-shortener-prod --services redirector --query 'services[0].deployments'
# Roll back
aws ecs update-service --cluster url-shortener-prod --service redirector \
  --task-definition redirector:<previous-revision> --force-new-deployment
```

Or re-run the `deploy` workflow from the last known-good commit.

## Scaling

- Both ECS services autoscale on CPU (target 60 %) and ALB `RequestCountPerTarget`. Hard limits are in
  `infra/terraform/ecs.tf` (`*_max_count`).
- Adding a Redis shard: add the node to `redis_node_count`, apply Terraform, then deploy **both** services with the
  updated node list in one go. Expect ~1/N cache misses for a few minutes.
- DynamoDB is on-demand; no action needed. For sustained >10k WCU consider provisioned + autoscaling (cheaper).

## Common operations

| Task | Command |
| --- | --- |
| Create a link | `curl -X POST $API/api/v1/urls -H "X-API-Key: $KEY" -d '{"longUrl":"https://..."}'` |
| Inspect an item | `aws dynamodb get-item --table-name short_urls --key '{"short_code":{"S":"abc123"}}'` |
| Which shard owns a key? | `curl $API/actuator/hashring?key=url:abc123` (admin endpoint, internal network only) |
| Evict a cache entry | `redis-cli -h <owning shard> DEL url:abc123` |
| Stream lag | `redis-cli -h <stream shard> XINFO GROUPS clicks` |

## Local reproduction

```bash
make up && make smoke
docker compose stop redis-2        # simulate a shard outage; watch RedisShardDown fire in Alertmanager
docker compose start redis-2
```
