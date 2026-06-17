package tools.vitruv.transactions.management.scheduling;

import static com.google.common.base.Preconditions.checkState;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;
import tools.vitruv.transactions.management.locking.C2PLThread;
import tools.vitruv.transactions.management.locking.LockManager;

/**
 * An abstract scheduler that holds an {@link InternalVirtualModel}, and is able to create
 * arbitrary {@link TransactionExecutorThread}s, and ensure their execution.
 *
 * <p>Subclasses of {@link AbstractScheduler} must implement {@code createNewExecutorThread}
 * in order to control how access to the multi-model environment happens.
 *
 * @param <E> Type of model elements that the MME manages.
 * @param <T> Type of transaction executor threads.
 */
public abstract class AbstractScheduler<E, T extends TransactionExecutorThread<E>>
    implements Scheduler<E, T> {
  /**
   * The multi-model environment where transactions are applied on.
   */
  protected final InternalVirtualModel multiModelEnvironment;

  /**
   * Timeout in milliseconds given for termination.
   */
  public static final long SHUTDOWN_TIMEOUT = 1000000000;
  /**
   * Maximum number of concurrently running transactions on {@code transactionThreadService}.
   */
  @Getter
  protected final int maximumConcurrentNumberOfThreads;
  /**
   * Executor service for transaction threads.
   */
  protected final ExecutorService transactionThreadService;

  /**
   * A flag that indicates whether to finish with execution of still running
   * {@link TransactionExecutorThread}s, or whether to still admit new transactions.
   */
  protected final AtomicBoolean finishExecution = new AtomicBoolean(false);
  /**
   * Counter for transactions/tasks that still need to be processed.
   * When this counter reaches 0, the transactions should all terminate.
   */
  protected final AtomicInteger submittedTasksCounter = new AtomicInteger(0);
  /**
   * A double-ended queue that holds finished results of {@link TransactionExecutorThread}s
   * in order to restart/finish blocked threads.
   */
  protected final BlockingDeque<TransactionExecutorThread.Result<E>> finishedThreadQueue
      = new LinkedBlockingDeque<>();
  /**
   * Lock manager used to determine if lock requests can be granted.
   */
  protected final LockManager<E> lockManager = new LockManager<>();

  @Override
  public VirtualModel getMultiModelEnvironment() {
    return multiModelEnvironment;
  }

  /**
   * Observers for scheduling events.
   */
  protected final ConcurrentLinkedDeque<SchedulingEventObserver<E>> observers
      = new ConcurrentLinkedDeque<>();

  /**
   * Creates a new {@link AbstractScheduler}.
   *
   * @param multiModelEnvironment {@link VirtualModel}
   * @param maximumConcurrentNumberOfThreads int
   */
  protected AbstractScheduler(InternalVirtualModel multiModelEnvironment,
                              int maximumConcurrentNumberOfThreads) {
    this.multiModelEnvironment = multiModelEnvironment;
    this.maximumConcurrentNumberOfThreads = maximumConcurrentNumberOfThreads;
    transactionThreadService = Executors.newFixedThreadPool(maximumConcurrentNumberOfThreads);
  }

  /**
   * Creates a new {@link TransactionExecutorThread} of subtype {@code T}
   * for {@code newTransaction}.
   *
   * @param newTransaction {@link TransactionState}
   * @return T
   */
  protected abstract T createNewExecutorThread(TransactionState<E> newTransaction);

  @Override
  public void addListener(SchedulingEventObserver<E> observer) {
    observers.add(observer);
  }

  @Override
  public void removeListener(SchedulingEventObserver<E> observer) {
    observers.remove(observer);
  }

  /**
   * Admits a new transaction for {@code change}, starts its execution,
   * and reports this to all observers.
   *
   * @param change - {@link VitruviusChange}
   * @return {@link TransactionState}
   * @throws IllegalStateException
   *     if {@link AbstractScheduler#waitForApplicationOfRunningTransactions}
   *     has been called
   */
  @Override
  public synchronized TransactionState<E> admitTransaction(VitruviusChange<E> change) {
    checkState(!this.finishExecution.get(),
        "Scheduler is shutting down and not accepting further changes!");

    submittedTasksCounter.incrementAndGet();
    var newTransaction = lockManager.submitTransaction(change);
    observers.forEach(observer -> observer.observeAdmission(newTransaction));
    var transactionThread = createNewExecutorThread(newTransaction);
    startExecutionOf(transactionThread);
    return newTransaction;
  }

  /**
   * Shuts down the {@code transactionThreadService} and waits
   * for currently executing transaction execution threads to finish.
   * While, or after calling this method, no other new transactions may be submitted through
   * {@link Scheduler#admitTransaction(VitruviusChange)}.
   *
   * @return true if all execution threads finished in time,
   *     false if they did not.
   */
  @Override
  public synchronized boolean waitForApplicationOfRunningTransactions() {
    // Until all tasks have completed:
    while (!submittedTasksCounter.compareAndSet(0, 0)) {
      // Retrieve next result
      TransactionExecutorThread.Result<E> result;
      try {
        result = finishedThreadQueue.take();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      if (result.status() == TransactionStatus.COMMITED || result.status() == TransactionStatus.ABORTED) {
        submittedTasksCounter.decrementAndGet();
      }
      // Resubmit all unblocked tasks
      for (var unblockedTransaction : result.unblockedTransactions()) {
        var newTransactionThread = createNewExecutorThread(unblockedTransaction);
        startExecutionOf(newTransactionThread);
      }
    }

    // Clean up
    transactionThreadService.shutdown();
    try {
      return transactionThreadService.awaitTermination(AbstractScheduler.SHUTDOWN_TIMEOUT,
          TimeUnit.MICROSECONDS);
    } catch (InterruptedException e) {
      return false;
    }
  }

  /**
   * Starts the execution of {@code transactionThread}.
   *
   * <p>This method constructs a {@code CompletableFuture}, that, when {@code transactionThread}
   * has stopped executing, reports its results to {@code finishedThreadQueue}.
   *
   * @param transactionThread - {@link C2PLThread}
   */
  protected void startExecutionOf(T transactionThread) {
    CompletableFuture.supplyAsync(() -> {
      try {
        var result = transactionThread.call();
        checkState(result.status() == TransactionStatus.ABORTED
            || result.status() == TransactionStatus.COMMITED
            || result.status() == TransactionStatus.BLOCKED);
        this.finishedThreadQueue.put(result);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
      return true;
    }, this.transactionThreadService)
        .exceptionallyAsync(throwable -> {
          System.err.println("Oops: " + throwable);
          return false;
        });
  }
}
