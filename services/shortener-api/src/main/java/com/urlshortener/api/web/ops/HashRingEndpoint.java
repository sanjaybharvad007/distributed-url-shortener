package com.urlshortener.api.web.ops;

import com.urlshortener.api.cache.RedisRing;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Operational endpoint: {@code GET /actuator/hashring} lists the shards; {@code GET
 * /actuator/hashring?key=url:abc123} shows which shard owns a key. Expose only on the internal
 * network.
 */
@Component
@Endpoint(id = "hashring")
public class HashRingEndpoint {

  private final RedisRing ring;

  public HashRingEndpoint(RedisRing ring) {
    this.ring = ring;
  }

  @ReadOperation
  public Map<String, Object> ring(@Nullable String key) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("nodes", ring.nodes());
    if (key != null && !key.isBlank()) {
      int idx = ring.shardFor(key);
      out.put("key", key);
      out.put("shardIndex", idx);
      out.put("shardNode", ring.nodes().get(idx));
    }
    return out;
  }
}
