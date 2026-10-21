package com.urlshortener.api.cache;

import com.urlshortener.api.hashring.ConsistentHashRing;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A set of standalone Redis shards addressed through a {@link ConsistentHashRing}.
 *
 * <p>Every logical key is routed to exactly one shard; the same routing is performed by the Go
 * redirector so cache populates and invalidations from either service land on the same node.
 */
public final class RedisRing implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RedisRing.class);

  private final ConsistentHashRing<String> ring;
  private final List<RedisClient> clients;
  private final List<StatefulRedisConnection<String, String>> connections;

  public RedisRing(List<String> nodes, int virtualNodes, Duration commandTimeout) {
    this.ring = new ConsistentHashRing<>(nodes, virtualNodes);
    List<RedisClient> cs = new ArrayList<>(nodes.size());
    List<StatefulRedisConnection<String, String>> conns = new ArrayList<>(nodes.size());
    for (String node : nodes) {
      RedisURI uri = RedisURI.create("redis://" + node);
      uri.setTimeout(commandTimeout);
      RedisClient client = RedisClient.create(uri);
      StatefulRedisConnection<String, String> conn = client.connect();
      conn.setTimeout(commandTimeout);
      cs.add(client);
      conns.add(conn);
      log.info("Connected Redis shard {} -> {}", cs.size() - 1, node);
    }
    this.clients = Collections.unmodifiableList(cs);
    this.connections = Collections.unmodifiableList(conns);
  }

  /** Index of the shard that owns {@code key}. */
  public int shardFor(String key) {
    return ring.indexFor(key);
  }

  /** Human-readable address of the shard that owns {@code key}. */
  public String nodeFor(String key) {
    return ring.nodeFor(key);
  }

  /** Synchronous command interface for the shard that owns {@code key}. */
  public RedisCommands<String, String> forKey(String key) {
    return connections.get(shardFor(key)).sync();
  }

  /** Synchronous command interface for a shard by index (ops / health checks). */
  public RedisCommands<String, String> shard(int index) {
    return connections.get(index).sync();
  }

  /**
   * Opens a <em>dedicated</em> connection to the shard that owns {@code key}. Blocking commands
   * (XREADGROUP ... BLOCK) must never run on the shared connection, because Redis queues every
   * other command from that client until the block ends. The caller owns and closes the connection.
   */
  public StatefulRedisConnection<String, String> dedicatedConnection(String key, Duration timeout) {
    StatefulRedisConnection<String, String> conn = clients.get(shardFor(key)).connect();
    conn.setTimeout(timeout);
    return conn;
  }

  public List<String> nodes() {
    return ring.nodes();
  }

  public int size() {
    return ring.size();
  }

  @Override
  public void close() {
    connections.forEach(StatefulRedisConnection::close);
    clients.forEach(RedisClient::shutdown);
  }
}
