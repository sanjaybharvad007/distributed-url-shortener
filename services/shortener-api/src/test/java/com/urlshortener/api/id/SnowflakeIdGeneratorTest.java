package com.urlshortener.api.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

  private static final long EPOCH = 1704067200000L;

  /** A clock whose reading we control from the test. */
  private static final class MutableClock extends Clock {
    final AtomicLong millis;

    MutableClock(long start) {
      millis = new AtomicLong(start);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
      return millis.get();
    }
  }

  @Test
  void idsAreUniqueAndMonotonicWithinAWorker() {
    SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7, EPOCH, Clock.systemUTC());
    long prev = -1;
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < 100_000; i++) {
      long id = gen.nextId();
      assertThat(id).isGreaterThan(prev);
      assertThat(seen.add(id)).isTrue();
      prev = id;
    }
  }

  @Test
  void encodesTimestampWorkerAndSequence() {
    MutableClock clock = new MutableClock(EPOCH + 1000);
    SnowflakeIdGenerator gen = new SnowflakeIdGenerator(5, EPOCH, clock);
    long first = gen.nextId();
    long second = gen.nextId();

    assertThat(first >>> SnowflakeIdGenerator.TIMESTAMP_SHIFT).isEqualTo(1000);
    assertThat((first >>> SnowflakeIdGenerator.WORKER_SHIFT) & SnowflakeIdGenerator.MAX_WORKER_ID)
        .isEqualTo(5);
    assertThat(first & SnowflakeIdGenerator.MAX_SEQUENCE).isEqualTo(0);
    assertThat(second & SnowflakeIdGenerator.MAX_SEQUENCE).isEqualTo(1);
  }

  @Test
  void differentWorkersNeverCollide() {
    MutableClock clock = new MutableClock(EPOCH + 42);
    SnowflakeIdGenerator a = new SnowflakeIdGenerator(1, EPOCH, clock);
    SnowflakeIdGenerator b = new SnowflakeIdGenerator(2, EPOCH, clock);
    Set<Long> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      assertThat(ids.add(a.nextId())).isTrue();
      assertThat(ids.add(b.nextId())).isTrue();
    }
  }

  @Test
  void rejectsOutOfRangeWorkerId() {
    assertThatThrownBy(() -> new SnowflakeIdGenerator(1024, EPOCH, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, EPOCH, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failsClosedOnLargeClockRollback() {
    MutableClock clock = new MutableClock(EPOCH + 10_000);
    SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, clock);
    gen.nextId();
    clock.millis.set(EPOCH + 5_000); // 5 s backwards
    assertThatThrownBy(gen::nextId).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void isThreadSafe() throws Exception {
    SnowflakeIdGenerator gen = new SnowflakeIdGenerator(3, EPOCH, Clock.systemUTC());
    Set<Long> ids = ConcurrentHashMap.newKeySet();
    int threads = 8;
    int perThread = 20_000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            for (int i = 0; i < perThread; i++) {
              ids.add(gen.nextId());
            }
          });
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    assertThat(ids).hasSize(threads * perThread);
  }
}
