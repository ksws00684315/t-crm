package com.tcrm.admin.link.service;

import com.tcrm.admin.shared.DistributedLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Long-running click consumer thread (task 06 §4.5). Only the instance holding the distributed lock
 * {@code link:click-consumer} consumes; the others retry every 10 s. Runs on its own thread instead
 * of {@code @Scheduled} so that it never occupies the shared scheduler.
 */
@Component
public class ClickConsumerRunner implements SmartLifecycle {
  static final String LOCK = "link:click-consumer";
  private static final Logger LOG = LoggerFactory.getLogger(ClickConsumerRunner.class);
  private final ClickConsumer consumer;
  private final ClickStream stream;
  private final DistributedLocks locks;
  private final boolean enabled;
  private final Duration retry;
  private volatile boolean running;
  private volatile Thread thread;

  public ClickConsumerRunner(
      ClickConsumer consumer,
      ClickStream stream,
      DistributedLocks locks,
      @Value("${crm.link.click-consumer.enabled:true}") boolean enabled,
      @Value("${crm.link.click-consumer.retry:10s}") Duration retry) {
    this.consumer = consumer;
    this.stream = stream;
    this.locks = locks;
    this.enabled = enabled;
    this.retry = retry;
  }

  @Override
  public void start() {
    if (!enabled || running) {
      return;
    }
    running = true;
    Thread worker = new Thread(this::loop, "click-consumer");
    worker.setDaemon(true);
    thread = worker;
    worker.start();
  }

  @Override
  public void stop() {
    running = false;
    Thread worker = thread;
    if (worker != null) {
      worker.interrupt();
      try {
        worker.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  void loop() {
    while (running) {
      try {
        // Non-positive lease: the watchdog keeps the lock while this instance is consuming.
        if (!locks.run(LOCK, 0, -1, this::consume)) {
          LOG.debug("Click consumer lock held by another instance; retrying in {}", retry);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        if (!running) {
          return;
        }
        LOG.error("Click consumer failed; retrying in {}", retry, e);
      }
      if (!pause()) {
        return;
      }
    }
  }

  /** Consumes until stopped; exceptions release the lock and are retried by {@link #loop}. */
  void consume() {
    stream.ensureGroup();
    LOG.info("Click consumer started");
    while (running && !Thread.currentThread().isInterrupted()) {
      consumer.pollOnce();
    }
  }

  private boolean pause() {
    try {
      Thread.sleep(retry.toMillis());
      return running;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
