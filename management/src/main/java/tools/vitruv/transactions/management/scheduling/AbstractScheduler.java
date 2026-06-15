package tools.vitruv.transactions.management.scheduling;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;

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
   * Counter for submitted tasks.
   * Used to determine _when_ to shut down the {@code transactionThreadService}.
   */
  protected final AtomicInteger submittedTasksCounter = new AtomicInteger(0);
  /**
   * Timeout in milliseconds given for termination.
   */
  public static final long SHUTDOWN_TIMEOUT = 1000000000;
  /**
   * The multi-model environment where transactions are applied on.
   */
  protected final InternalVirtualModel multiModelEnvironment;
  /**
   * Maximum number of concurrently running transactions on {@code transactionThreadService}.
   */
  @Getter
  protected final int maximumConcurrentNumberOfThreads;
  /**
   * Executor service for transaction threads.
   */
  protected final ExecutorService transactionThreadService;

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
  protected AbstractScheduler(InternalVirtualModel multiModelEnvironment, int maximumConcurrentNumberOfThreads) {
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
   * Shuts down the {@code transactionThreadService} and waits
   * for currently executing transaction execution threads to finish.
   *
   * @return true if all execution threads finished in time,
   *     false if they did not.
   */
  @Override
  public boolean waitForApplicationOfRunningTransactions() {
    // Wait for all threads to stop executing
    synchronized (submittedTasksCounter) {
      while (!submittedTasksCounter.compareAndSet(0, 0)) {
        try {
          submittedTasksCounter.wait();
        } catch (InterruptedException e) {}
      }
    }

    transactionThreadService.shutdown();
    try {
      return transactionThreadService.awaitTermination(AbstractScheduler.SHUTDOWN_TIMEOUT,
          TimeUnit.MICROSECONDS);
    } catch (InterruptedException e) {
      return false;
    }
  }
}
