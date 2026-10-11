package com.tcrm.admin.link.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.util.Objects;
import java.util.Properties;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute: click stream backlog of the {@code crm-admin} group against {@code
 * TCRM_CLICK_STREAM_MAXLEN} (WARN above 50%, ERROR above 80%) and Redis {@code used_memory} against
 * {@code maxmemory} (WARN above 70%, ERROR above 85%, skipped without a limit). Only logs; in-app
 * notifications come with a later task.
 */
@Component
public class ClickStreamMonitor {
  static final String LOCK = "link:click-monitor";
  static final double BACKLOG_WARN = 0.5;
  static final double BACKLOG_ERROR = 0.8;
  static final double MEMORY_WARN = 0.7;
  static final double MEMORY_ERROR = 0.85;
  private static final Logger LOG = LoggerFactory.getLogger(ClickStreamMonitor.class);
  private final RedissonClient redisson;
  private final StringRedisTemplate redis;
  private final DistributedLocks locks;
  private final long maxLength;

  /** Severity of a measured value against its limit. */
  enum Level {
    OK,
    WARN,
    ERROR
  }

  /** Snapshot of the stream and the group (group fields are null when the group does not exist). */
  record StreamState(
      long length, String lastGeneratedId, Long lag, Long pending, String lastDeliveredId) {}

  public ClickStreamMonitor(
      RedissonClient redisson,
      StringRedisTemplate redis,
      DistributedLocks locks,
      @Value("${crm.link.click-stream-max-len}") long maxLength) {
    if (maxLength <= 0) {
      throw new IllegalStateException("TCRM_CLICK_STREAM_MAXLEN must be a positive number");
    }
    this.redisson = redisson;
    this.redis = redis;
    this.locks = locks;
    this.maxLength = maxLength;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void scheduled() throws InterruptedException {
    locks.run(LOCK, 0, 50, this::check);
  }

  void check() {
    TenantScope.runSystem("read click stream backlog", this::checkInScope);
  }

  private void checkInScope() {
    try {
      long backlog = backlog(streamState());
      log(
          level(backlog, maxLength, BACKLOG_WARN, BACKLOG_ERROR),
          "Click stream backlog",
          backlog,
          maxLength);
    } catch (RuntimeException e) {
      LOG.warn("Click stream backlog check failed", e);
    }
    try {
      Properties memory =
          redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("memory"));
      long used = number(memory, "used_memory");
      long max = number(memory, "maxmemory");
      if (max > 0) {
        log(level(used, max, MEMORY_WARN, MEMORY_ERROR), "Redis used_memory", used, max);
      }
    } catch (RuntimeException e) {
      LOG.warn("Redis memory check failed", e);
    }
  }

  /**
   * Backlog of the group: {@code lag} from XINFO GROUPS when known. Redis reports lag as nil when
   * it cannot be computed (e.g. after trimming or deletions), which Redisson decodes as 0; a 0 lag
   * while the group has not delivered the newest entry is therefore treated as unknown and
   * estimated conservatively as max(XLEN, pending). Without the group, everything in the stream is
   * backlog.
   */
  static long backlog(StreamState state) {
    if (state.lag() == null) {
      return state.length();
    }
    boolean unknown =
        state.lag() == 0 && !Objects.equals(state.lastDeliveredId(), state.lastGeneratedId());
    if (!unknown) {
      return state.lag();
    }
    return Math.max(state.length(), state.pending() == null ? 0 : state.pending());
  }

  /**
   * ERROR above {@code errorRatio} of the limit, WARN above {@code warnRatio}; OK without a limit.
   */
  static Level level(long value, long limit, double warnRatio, double errorRatio) {
    if (limit <= 0) {
      return Level.OK;
    }
    double ratio = (double) value / limit;
    if (ratio > errorRatio) {
      return Level.ERROR;
    }
    return ratio > warnRatio ? Level.WARN : Level.OK;
  }

  private StreamState streamState() {
    var stream = redisson.getStream(ClickStream.STREAM, StringCodec.INSTANCE);
    if (!stream.isExists()) {
      return new StreamState(0, null, 0L, 0L, null);
    }
    var info = stream.getInfo();
    String lastGenerated = Objects.toString(info.getLastGeneratedId(), null);
    for (StreamGroup group : stream.listGroups()) {
      if (ClickStream.GROUP.equals(group.getName())) {
        return new StreamState(
            info.getLength(),
            lastGenerated,
            (long) group.getLag(),
            (long) group.getPending(),
            Objects.toString(group.getLastDeliveredId(), null));
      }
    }
    return new StreamState(info.getLength(), lastGenerated, null, null, null);
  }

  private static void log(Level level, String what, long value, long limit) {
    long percent = Math.round(value * 100.0 / limit);
    switch (level) {
      case ERROR -> LOG.error("{} at {}% of the limit ({} of {})", what, percent, value, limit);
      case WARN -> LOG.warn("{} at {}% of the limit ({} of {})", what, percent, value, limit);
      default -> LOG.debug("{} at {}% of the limit ({} of {})", what, percent, value, limit);
    }
  }

  private static long number(Properties properties, String name) {
    String value = properties == null ? null : properties.getProperty(name);
    return value == null ? 0 : Long.parseLong(value.trim());
  }
}
