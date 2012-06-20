/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.modules.ehcache.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.modules.ehcache.async.errorhandlers.AsyncErrorHandler;
import org.terracotta.modules.ehcache.async.exceptions.BusyProcessingException;
import org.terracotta.modules.ehcache.async.exceptions.ExistingRunningThreadException;
import org.terracotta.modules.ehcache.async.exceptions.ProcessingException;
import org.terracotta.toolkit.cluster.ClusterInfo;
import org.terracotta.toolkit.collections.ToolkitList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ProcessingBucket<E extends Serializable> {
  private static final Logger          LOGGER             = LoggerFactory.getLogger(ProcessingBucket.class.getName());
  private final String                 bucketName;
  private final AsyncConfig            config;
  private final ClusterInfo            cluster;
  private final ItemProcessor<E>       processor;
  private final AsyncErrorHandler      errorHandler;
  private volatile ItemsFilter<E>      filter;
  private final long                   baselineTimestamp;
  private final ReentrantReadWriteLock bucketLock;
  private final Lock                   bucketWriteLock;
  private final Lock                   bucketReadLock;
  private final Condition              bucketIsEmpty;
  private final Condition              bucketIsFull;
  private final Condition              stoppedButBucketNotEmpty;
  private final ToolkitList<E>         toolkitList;
  private long                         lastProcessing     = -1;
  private final AtomicLong             lastWorkDoneMillis = new AtomicLong(-1);
  private final AtomicBoolean          busy               = new AtomicBoolean(false);
  private volatile boolean             cancelled          = false;
  private final AtomicLong             workDelay;
  private ProcessingWorker             processingWorker;
  private Callable<Boolean>            destroyCallback;

  public ProcessingBucket(String bucketName, AsyncConfig config, ToolkitList toolkitList, ClusterInfo cluster,
                          ItemProcessor<E> processor, AsyncErrorHandler errorHandler) {
    this.bucketName = bucketName;
    this.config = config;
    this.cluster = cluster;
    this.processor = processor;
    this.toolkitList = toolkitList;
    this.baselineTimestamp = System.currentTimeMillis();
    this.bucketLock = new ReentrantReadWriteLock();
    this.bucketReadLock = bucketLock.readLock();
    this.bucketWriteLock = bucketLock.writeLock();
    this.bucketIsEmpty = bucketWriteLock.newCondition();
    this.bucketIsFull = bucketWriteLock.newCondition();
    this.stoppedButBucketNotEmpty = bucketWriteLock.newCondition();
    this.errorHandler = errorHandler;
    this.workDelay = new AtomicLong(config.getWorkDelay());
  }

  public String getBucketName() {
    return bucketName;
  }

  /**
   * @return returns recent time stamp when processItems() executed.
   */
  public long getLastProcessing() {
    Lock lock = bucketReadLock;
    lock.lock();
    try {
      return lastProcessing;
    } finally {
      lock.unlock();
    }
  }

  public void setItemsFilter(ItemsFilter<E> filter) {
    this.filter = filter;
  }

  private long baselinedCurrentTimeMillis() {
    return System.currentTimeMillis() - baselineTimestamp;
  }

  void start(boolean workingOnDeadBucket) throws ExistingRunningThreadException {
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      ensureNonExistingThread();
      processingWorker = new ProcessingWorker("ProcessingWorker-" + bucketName, workingOnDeadBucket);
      processingWorker.setDaemon(true);
      processingWorker.start();
    } finally {
      lock.unlock();
    }
  }

  private void ensureNonExistingThread() throws ExistingRunningThreadException {
    if (processingWorker != null && processingWorker.isWorking()) { throw new ExistingRunningThreadException(
                                                                                                             processingWorker); }
  }

  private boolean isCancelled() {
    try {
      Lock lock = bucketReadLock;
      lock.lock();
      try {
        return cancelled || (processingWorker.isWorkingOnDeadBucket() && toolkitList.isEmpty());
      } finally {
        lock.unlock();
      }
    } catch (RuntimeException e) {
      if (e.getClass().getName().equals("com.tc.exception.TCNotRunningException")) {
        return true;
      } else {
        throw e;
      }
    }
  }

  public int getWaitCount() {
    Lock lock = bucketReadLock;
    lock.lock();
    try {
      return toolkitList.size();
    } finally {
      lock.unlock();
    }
  }

  public void stop() {
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      signalNotEmpty();
      try {
        workDelay.set(0);
        while (!toolkitList.isEmpty()) {
          stoppedButBucketNotEmpty.await();
        }
        cancelled = true;
        if (!processingWorker.isWorkingOnDeadBucket()) {
          destroyToolkitList();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } finally {
      lock.unlock();
    }
  }

  private void destroyToolkitList() {
    toolkitList.destroy();
    if (destroyCallback != null) {
      try {
        Boolean isRemoved = destroyCallback.call();
        if (!isRemoved) { throw new IllegalStateException("bucket " + bucketName
                                                          + " not found in localBuckets list");
        }
      } catch (Exception e) {
        throw new IllegalStateException("Exception while removing bucket "+ bucketName+" from localBuckets list " + e);
      }
    }
  }

  public String getThreadName() {
    Lock lock = bucketReadLock;
    lock.lock();
    try {
      if (null == processingWorker) { return null; }
      return processingWorker.getName();
    } finally {
      lock.unlock();
    }
  }

  public void add(final E item) {
    if (null == item) return;
    int maxQueueSize = config.getMaxQueueSize();
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      if (maxQueueSize != 0) {
        while (toolkitList.size() >= maxQueueSize) {
          try {
            bucketIsFull.await();
          } catch (final InterruptedException e) {
            // if the wait for items is interrupted, act as if the bucket was canceled
            stop();
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
      toolkitList.add(item);
      if (toolkitList.size() + 1 < maxQueueSize) {
        signalNotFull();
      }
      signalNotEmpty();
    } finally {
      lock.unlock();
    }
  }

  private int determineBatchSize() {
    int batchSize = config.getBatchSize();
    int listSize = toolkitList.size();
    if (listSize < batchSize) {
      batchSize = listSize;
    }
    return batchSize;
  }

  private void filterQuarantined() {
    if (null == filter) { return; }

    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      ItemsFilter<E> itemsFilter = this.filter;
      if (itemsFilter != null) {
        itemsFilter.filter(toolkitList);
      }
    } finally {
      lock.unlock();
    }
  }

  private void signalNotFull() {
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      bucketIsFull.signal();
    } finally {
      lock.unlock();
    }
  }

  private void signalNotEmpty() {
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      bucketIsEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * This method process items from bucket. Execution of this method does not guarantee that items from a non empty
   * bucket will be processed.
   */
  private void processItems() throws BusyProcessingException, ProcessingException {
    // set some state related to this processing run
    final int workSize;
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      if (busy.get()) { throw new BusyProcessingException(); }
      if (cancelled) { return; }
      busy.set(true);
      lastProcessing = baselinedCurrentTimeMillis();
      workSize = toolkitList.size();
      // if there's no work that needs to be done, stop the processing
      if (0 == workSize) {
        signalNotFull();
        LOGGER.warn(getThreadName() + " : processItems() : nothing to process");
        return;
      }

      filterQuarantined();
      // if the batching is enabled and work size is smaller than batch size, don't process anything as long as the
      // max allowed fall behind delay hasn't expired
      final int batchSize = config.getBatchSize();
      if (config.isBatchingEnabled() && batchSize > 0) {
        // wait for another round if the batch size hasn't been filled up yet and the max write delay
        // hasn't expired yet
        if (workSize < batchSize && config.getMaxAllowedFallBehind() > lastProcessing - lastWorkDoneMillis.get()) {
          LOGGER.warn(getThreadName() + " : processItems() : only " + workSize + " work items available, waiting for "
                      + batchSize + " items to fill up a batch");
          return;
        }

        // enforce the rate limit and wait for another round if too much would be processed compared to
        // the last time when a batch was executed
        final int rateLimit = config.getRateLimit();
        if (rateLimit > 0) {
          final long secondsSinceLastWorkDone;
          final int effectiveBatchSize;
          lock = bucketReadLock;
          lock.lock();
          try {
            secondsSinceLastWorkDone = (baselinedCurrentTimeMillis() - lastWorkDoneMillis.get()) / 1000;
            effectiveBatchSize = determineBatchSize();
          } finally {
            lock.unlock();
          }
          final long maxBatchSizeSinceLastWorkDone = rateLimit * secondsSinceLastWorkDone;
          if (effectiveBatchSize > maxBatchSizeSinceLastWorkDone) {
            LOGGER.warn(getThreadName() + " : processItems() : last work was done " + secondsSinceLastWorkDone
                        + " seconds ago, processing " + effectiveBatchSize
                        + " batch items would exceed the rate limit of " + rateLimit + ", waiting for a while.");
            return;
          }
        }
      }

      // set some state related to this processing run
      lastWorkDoneMillis.set(baselinedCurrentTimeMillis());
      doProcessItems();
    } finally {
      lock = bucketWriteLock;
      lock.lock();
      try {
        busy.set(false);
      } finally {
        lock.unlock();
      }
    }
  }

  private void doProcessItems() throws ProcessingException {
    // process the quarantined items and remove them as they're processed
    Lock lock = bucketWriteLock;
    lock.lock();
    try {
      // don't process work if this node's operations have been disabled
      if (cluster != null && !cluster.areOperationsEnabled()) {
        signalNotEmpty();
        return;
      } else {
        if (config.isBatchingEnabled() && config.getBatchSize() > 0) {
          processBatchedItems();
        } else {
          processSingleItem();
        }
        signalNotFull();

        if (toolkitList.isEmpty()) {
          stoppedButBucketNotEmpty.signalAll();
        } else {
          signalNotEmpty();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void processSingleItem() throws ProcessingException {
    // process the next item
    final E item = toolkitList.get(0);
    final int retryAttempts = config.getRetryAttempts();
    int executionsLeft = retryAttempts + 1;
    while (executionsLeft-- > 0) {
      try {
        processor.process(item);
        break;
      } catch (final RuntimeException e) {
        if (executionsLeft <= 0) {
          processor.throwAway(item, e);
        } else {
          LOGGER.warn(getThreadName() + " : processSingleItem() : exception during processing, retrying in "
                      + retryAttempts + " milliseconds, " + executionsLeft + " retries left : " + e.getMessage());
          try {
            Thread.sleep(config.getRetryAttemptDelay());
          } catch (InterruptedException e1) {
            Thread.currentThread().interrupt();
            throw e;
          }
        }
      }
    }
    toolkitList.remove(0);
  }

  private void processBatchedItems() throws ProcessingException {
    final int effectiveBatchSize = determineBatchSize();

    List<E> batch = new ArrayList<E>(effectiveBatchSize);
    for (int i = 0; i < effectiveBatchSize; i++) {
      final E item = toolkitList.get(i);
      batch.add(item);
    }
    final int retryAttempts = config.getRetryAttempts();
    int executionsLeft = retryAttempts + 1;
    while (executionsLeft-- > 0) {
      try {
        processor.process(batch);
        break;
      } catch (final RuntimeException e) {
        if (executionsLeft <= 0) {
          for (E item : batch) {
            processor.throwAway(item, e);
          }
        } else {
          LOGGER.warn(getThreadName() + " : processBatchedItems() : exception during processing, retrying in "
                      + retryAttempts + " milliseconds, " + executionsLeft + " retries left : " + e.getMessage());
          try {
            Thread.sleep(config.getRetryAttemptDelay());
          } catch (InterruptedException e1) {
            Thread.currentThread().interrupt();
            throw e;
          }
        }
      }
    }

    for (int i = 0; i < effectiveBatchSize; i++) {
      toolkitList.remove(0);
    }
  }

  public void setDestroyCallback(Callable<Boolean> removeOnDestroy) {
    this.destroyCallback = removeOnDestroy;
  }

  private final class ProcessingWorker extends Thread {
    private boolean       isRunning = false;
    private final boolean workingOnDeadBucket;

    public ProcessingWorker(String threadName, boolean workingOnDeadBucket) {
      super(threadName);
      this.workingOnDeadBucket = workingOnDeadBucket;
    }

    @Override
    public void run() {
      isRunning = true;
      try {
        while (!isCancelled()) {
          // process the items if this node's operations are enabled
          if (null != cluster || cluster.areOperationsEnabled()) {
            try {
              processItems();
            } catch (final Throwable e) {
              if (null == cluster || cluster.areOperationsEnabled()) {
                errorHandler.onError(ProcessingBucket.this, e);
              } else {
                LOGGER.warn("Caught error on processing items, but looks like we were shut down. "
                            + "This can probably be safely ignored", e);
              }
              continue;
            }
          }

          final long currentLastProcessing = getLastProcessing();

          // Wait for new items or until the work delay has expired.
          // Do not continue if the actual work delay wasn't at least the one specified in the config
          // otherwise it's possible to create a new work list for just a couple of items in case
          // the item processor is very fast, causing a large amount of data churn and broadcasts.
          // However, if the work delay is expired, the processing should start immediately.
          Lock lock = bucketWriteLock;
          lock.lock();
          try {
            try {
              long tmpWorkDelay = workDelay.get();
              if (workDelay.get() != 0) {
                do {
                  bucketIsEmpty.await(tmpWorkDelay, TimeUnit.MILLISECONDS);
                  long actualWorkDelay = baselinedCurrentTimeMillis() - currentLastProcessing;
                  if (actualWorkDelay < tmpWorkDelay) {
                    tmpWorkDelay -= actualWorkDelay;
                  } else {
                    tmpWorkDelay = 0;
                  }
                } while (tmpWorkDelay > 0);
              } else {
                while (toolkitList.isEmpty()) {
                  bucketIsEmpty.await();
                }
              }
            } catch (final InterruptedException e) {
              // if the wait for items is interrupted, act as if the bucket was canceled
              ProcessingBucket.this.stop();
              Thread.currentThread().interrupt();
            }

          } finally {
            lock.unlock();
          }
        }
      } catch (Throwable t) {
        if (t.getClass().getName().equals("com.tc.exception.TCNotRunningException") && cluster != null
            && !cluster.areOperationsEnabled()) {
          LOGGER.warn("Caught TCNotRunningException on processing thread, but looks like we were shut down. "
                      + "This can safely be ignored!", t);
        }
      } finally {
        //
      }

      // Destroying buckets with no owners
      if (processingWorker.isWorkingOnDeadBucket()) {
        destroyToolkitList();
      }
      isRunning = false;
    }

    public boolean isWorking() {
      return isRunning;
    }

    public boolean isWorkingOnDeadBucket() {
      return workingOnDeadBucket;
    }
  }

}
