package com.urlshortener.api.id;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Twitter-Snowflake style 64-bit ID generator.
 *
 * <pre>
 *  0 | 41 bits: milliseconds since custom epoch | 10 bits: worker id | 12 bits: sequence
 * </pre>
 *
 * <p>Produces up to 4096 unique IDs per millisecond per worker with no coordination between
 * workers. IDs are roughly time-ordered. If the clock moves backwards the generator spins for up to
 * {@value #MAX_BACKWARDS_WAIT_MS} ms and then fails closed rather than risk a duplicate.
 */
public final class SnowflakeIdGenerator implements IdGenerator {

  static final long WORKER_ID_BITS = 10L;
  static final long SEQUENCE_BITS = 12L;
  public static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1; // 1023
  static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 4095
  static final long WORKER_SHIFT = SEQUENCE_BITS;
  static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
  private static final long MAX_BACKWARDS_WAIT_MS = 10;

  private final long workerId;
  private final long epochMillis;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();

  private long lastTimestamp = -1L;
  private long sequence = 0L;

  public SnowflakeIdGenerator(long workerId, long epochMillis, Clock clock) {
    if (workerId < 0 || workerId > MAX_WORKER_ID) {
      throw new IllegalArgumentException(
          "workerId must be between 0 and " + MAX_WORKER_ID + ", got " + workerId);
    }
    this.workerId = workerId;
    this.epochMillis = epochMillis;
    this.clock = clock;
  }

  public long workerId() {
    return workerId;
  }

  @Override
  public long nextId() {
    lock.lock();
    try {
      long now = clock.millis();
      if (now < lastTimestamp) {
        long drift = lastTimestamp - now;
        if (drift > MAX_BACKWARDS_WAIT_MS) {
          throw new IllegalStateException(
              "Clock moved backwards by " + drift + "ms; refusing to generate IDs");
        }
        now = waitUntil(lastTimestamp);
      }
      if (now == lastTimestamp) {
        sequence = (sequence + 1) & MAX_SEQUENCE;
        if (sequence == 0) {
          // Sequence exhausted for this millisecond; wait for the next one.
          now = waitUntil(lastTimestamp + 1);
        }
      } else {
        sequence = 0L;
      }
      lastTimestamp = now;
      return ((now - epochMillis) << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    } finally {
      lock.unlock();
    }
  }

  private long waitUntil(long target) {
    long now = clock.millis();
    while (now < target) {
      Thread.onSpinWait();
      now = clock.millis();
    }
    return now;
  }
}
