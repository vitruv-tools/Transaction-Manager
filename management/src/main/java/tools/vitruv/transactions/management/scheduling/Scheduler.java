package tools.vitruv.transactions.management.scheduling;

import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.transactions.management.TransactionState;

/**
 * A generic scheduler interface.
 *
 * <p>Takes complete {@link VitruviusChange}s and ensures their transactional
 * application on a {@link VirtualModel}, which acts as multi-model environment.
 *
 * <p>Schedulers may process {@link VitruviusChange}s asynchronously.
 * Schedulers inform other parties through the {@link SchedulingEventObserver} interface
 * when transactions finish (either through commit or abort).
 *
 * <p>Schedulers delegate the application of transactions to {@link TransactionExecutorThread}s.
 * Multiple transactions may run concurrently.
 *
 * @param <E> - Type of model elements an environment holds.
 * @param <T> - Type of transaction executor threads.
 */
public interface Scheduler<E, T extends TransactionExecutorThread<E>> {
  /**
   * Returns the environment for which transactions are scheduled.
   *
   * @return multiModelEnvironment - {@link VirtualModel}
   */
  VirtualModel getMultiModelEnvironment();

  /**
   * Returns the maximum number of {@link TransactionExecutorThread}s that may apply
   * a transaction on {@link Scheduler#getMultiModelEnvironment} at the same time.
   *
   * @return int
   */
  int getMaximumConcurrentNumberOfThreads();

  /**
   * Admits {@code change} and starts to execute the {@link TransactionExecutorThread}
   * that processes the new transaction.
   *
   * @param change - {@link VitruviusChange}
   * @return {@link TransactionState}
   */
  TransactionState<E> admitTransaction(VitruviusChange<E> change);

  /**
   * Adds {@code observer} to observe scheduling events.
   *
   * @param observer - {@link SchedulingEventObserver}
   */
  void addListener(SchedulingEventObserver<E> observer);

  /**
   * Removes {@code observer} as scheduling event observer.
   *
   * @param observer - {@link SchedulingEventObserver}
   */
  void removeListener(SchedulingEventObserver<E> observer);

  /**
   * Waits for all currently running {@link TransactionExecutorThread}s to finish execution.
   *
   * @return boolean {@code true} if and only if all running threads have stopped execution.
   */
  boolean waitForApplicationOfRunningTransactions();
}
