package com.urlshortener.api.click;

import com.urlshortener.api.cache.RedisKeys;
import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.config.AppProperties;
import com.urlshortener.api.repository.ShortUrlRepository;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code clicks} stream with a consumer group and applies click counts to DynamoDB in
 * aggregated batches (write-behind, ADR 0004). At-least-once: messages are acknowledged only after
 * the DynamoDB write succeeds, and entries stuck in another consumer's pending list for more than
 * {@link #CLAIM_IDLE} are reclaimed with {@code XAUTOCLAIM}.
 */
@Component
public class ClickConsumer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ClickConsumer.class);
  private static final Duration CLAIM_IDLE = Duration.ofSeconds(30);
  private static final Duration CLAIM_INTERVAL = Duration.ofSeconds(15);
  private static final long READ_BLOCK_MS = 250;

  private final RedisRing ring;
  private final ShortUrlRepository repository;
  private final AppProperties.ClickConsumer cfg;
  private final String consumerName;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lagGauge = new AtomicLong();
  private final Counter processed;
  private final Counter failed;
  private Thread worker;
  private volatile StatefulRedisConnection<String, String> connection;

  public ClickConsumer(
      RedisRing ring, ShortUrlRepository repository, AppProperties props, MeterRegistry registry) {
    this.ring = ring;
    this.repository = repository;
    this.cfg = props.clickConsumer();
    this.consumerName = hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    this.processed = Counter.builder("click_events_processed_total").register(registry);
    this.failed = Counter.builder("click_events_failed_total").register(registry);
    Gauge.builder("click_consumer_lag_events", lagGauge, AtomicLong::get)
        .description("Entries pending in the clicks consumer group")
        .register(registry);
  }

  // ---------------------------------------------------------------- lifecycle

  @Override
  public void start() {
    if (!cfg.enabled() || !running.compareAndSet(false, true)) {
      return;
    }
    // Dedicated connection: XREADGROUP ... BLOCK must not stall the shared shard connection.
    connection =
        ring.dedicatedConnection(
            RedisKeys.CLICK_STREAM, Duration.ofMillis(READ_BLOCK_MS).plusSeconds(2));
    ensureGroup();
    worker = Thread.ofPlatform().name("click-consumer").daemon(true).start(this::loop);
    log.info("click consumer {} started on group {}", consumerName, cfg.group());
  }

  @Override
  public void stop() {
    if (running.compareAndSet(true, false) && worker != null) {
      worker.interrupt();
      try {
        worker.join(Duration.ofSeconds(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (connection != null) {
        connection.close();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100; // start late, stop early
  }

  // ---------------------------------------------------------------- consumer loop

  private RedisCommands<String, String> stream() {
    return connection.sync();
  }

  private void ensureGroup() {
    try {
      stream()
          .xgroupCreate(
              XReadArgs.StreamOffset.from(RedisKeys.CLICK_STREAM, "0"),
              cfg.group(),
              XGroupCreateArgs.Builder.mkstream());
    } catch (RedisBusyException alreadyExists) {
      // BUSYGROUP: group exists, fine.
    } catch (RedisException e) {
      log.warn("could not create consumer group yet: {}", e.toString());
    }
  }

  void loop() {
    long lastClaim = System.nanoTime();
    long backoffMs = 100;
    while (running.get()) {
      try {
        List<StreamMessage<String, String>> batch = readBatch();
        if (System.nanoTime() - lastClaim > CLAIM_INTERVAL.toNanos()) {
          batch.addAll(claimStale());
          lastClaim = System.nanoTime();
          updateLag();
        }
        if (!batch.isEmpty()) {
          process(batch);
        }
        backoffMs = 100;
      } catch (RedisException e) {
        log.warn("click consumer redis error, retrying in {}ms: {}", backoffMs, e.toString());
        sleep(backoffMs);
        backoffMs = Math.min(backoffMs * 2, 5_000);
        ensureGroup();
      } catch (RuntimeException e) {
        failed.increment();
        log.error("click consumer batch failed; entries stay pending for reclaim", e);
        sleep(backoffMs);
      }
    }
    log.info("click consumer {} stopped", consumerName);
  }

  private List<StreamMessage<String, String>> readBatch() {
    List<StreamMessage<String, String>> msgs =
        stream()
            .xreadgroup(
                Consumer.from(cfg.group(), consumerName),
                XReadArgs.Builder.count(cfg.batchSize()).block(READ_BLOCK_MS),
                XReadArgs.StreamOffset.lastConsumed(RedisKeys.CLICK_STREAM));
    return msgs == null ? new ArrayList<>() : new ArrayList<>(msgs);
  }

  private List<StreamMessage<String, String>> claimStale() {
    try {
      return stream()
          .xautoclaim(
              RedisKeys.CLICK_STREAM,
              XAutoClaimArgs.Builder.xautoclaim(
                      Consumer.from(cfg.group(), consumerName), CLAIM_IDLE.toMillis(), "0-0")
                  .count(cfg.batchSize()))
          .getMessages();
    } catch (RedisException e) {
      log.debug("xautoclaim failed: {}", e.toString());
      return List.of();
    }
  }

  private void updateLag() {
    try {
      lagGauge.set(stream().xpending(RedisKeys.CLICK_STREAM, cfg.group()).getCount());
    } catch (RedisException ignored) {
      // metric is best-effort
    }
  }

  /** Aggregates per code, writes to DynamoDB, then acks. Visible for tests. */
  void process(List<StreamMessage<String, String>> batch) {
    Map<String, Long> deltas = new HashMap<>();
    List<String> ids = new ArrayList<>(batch.size());
    for (StreamMessage<String, String> m : batch) {
      ids.add(m.getId());
      String code = m.getBody().get(ClickEvent.F_CODE);
      if (code != null && !code.isEmpty()) {
        deltas.merge(code, 1L, Long::sum);
      }
    }
    if (!deltas.isEmpty()) {
      repository.addClicks(deltas);
    }
    stream().xack(RedisKeys.CLICK_STREAM, cfg.group(), ids.toArray(String[]::new));
    processed.increment(batch.size());
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "api";
    }
  }
}
