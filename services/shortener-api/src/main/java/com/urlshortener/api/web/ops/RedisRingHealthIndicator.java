package com.urlshortener.api.web.ops;

import com.urlshortener.api.cache.RedisRing;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports each shard individually. The service is DOWN only if every shard is unreachable (the
 * cache is optional); a partial outage is reported as UP with details so dashboards can see it.
 */
@Component // bean "redisRingHealthIndicator" -> health contributor "redisRing"
public class RedisRingHealthIndicator implements HealthIndicator {

  private final RedisRing ring;

  public RedisRingHealthIndicator(RedisRing ring) {
    this.ring = ring;
  }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    int up = 0;
    for (int i = 0; i < ring.size(); i++) {
      String node = ring.nodes().get(i);
      try {
        String pong = ring.shard(i).ping();
        boolean ok = "PONG".equalsIgnoreCase(pong);
        details.put(node, ok ? "UP" : "DOWN");
        if (ok) {
          up++;
        }
      } catch (RuntimeException e) {
        details.put(node, "DOWN: " + e.getClass().getSimpleName());
      }
    }
    details.put("shardsUp", up + "/" + ring.size());
    return (up == 0 ? Health.down() : Health.up()).withDetails(details).build();
  }
}
