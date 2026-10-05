package com.tcrm.admin.task.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounded worker pool for batch tasks: 2 threads, 20 queued tasks, rejection when full. Not exposed
 * as an {@code Executor} bean so that Spring's default application task executor stays in place.
 */
@Component
public class TaskQueue {
  static final int THREADS = 2;
  static final int CAPACITY = 20;
  private final ExecutorService executor;

  @Autowired
  public TaskQueue() {
    this(
        new ThreadPoolExecutor(
            THREADS,
            THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CAPACITY),
            namedThreads(),
            new ThreadPoolExecutor.AbortPolicy()));
  }

  TaskQueue(ExecutorService executor) {
    this.executor = executor;
  }

  /** Queues the work; throws TASK_QUEUE_FULL (429) when all threads are busy and the queue full. */
  public void submit(Runnable work) {
    try {
      executor.execute(work);
    } catch (RejectedExecutionException e) {
      throw new BusinessException(ErrorCode.TASK_QUEUE_FULL);
    }
  }

  /** Stops accepting work; running tasks are interrupted and become FAILED on the next start. */
  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  private static ThreadFactory namedThreads() {
    AtomicInteger sequence = new AtomicInteger();
    return work -> {
      Thread thread = new Thread(work, "batch-task-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
