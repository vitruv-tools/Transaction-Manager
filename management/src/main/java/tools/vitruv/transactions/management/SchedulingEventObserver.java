package tools.vitruv.transactions.management;

import java.util.Collection;
import tools.vitruv.change.atomic.EChange;

/**
 * An observer to scheduling events that a {@link Scheduler} emits.
 * Such events are:
 *
 * <ol>
 *     <li>Admitting a new transaction,</li>
 *     <li>Setting a transaction to running,</li>
 *     <li>Blocking a transaction,</li>
 *     <li>Executing and undoing one step of a transaction,</li>
 *     <li>Commiting or aborting a transaction.</li>
 * </ol>
 *
 * @param <E> - Type of model elements an environment managed by a scheduler holds.
 */
public interface SchedulingEventObserver<E> {
  /**
   * Observe that {@code newTransaction} has been submitted to the {@code scheduler}.
   *
   * @param newTransaction {@link Transaction}
   */
  default void observeAdmission(Transaction<E> newTransaction) {}

  /**
   * Observe that {@code running} can now start to execute operations.
   *
   * @param running {@link Transaction}
   */
  default void observeRunning(Transaction<E> running) {}

  /**
   * Observe that {@code forTransaction} has executed {@code step}.
   *
   * @param step {@link EChange}
   * @param forTransaction {@link Transaction}
   */
  default void observeExecutionOf(EChange<E> step, Transaction<E> forTransaction) {}

  /**
   * Observe that {@code blockedTransaction} is blocked by the set of {@code blockingTransactions},
   * and is waiting for their further execution.
   *
   * @param blockedTransaction {@link Transaction}
   * @param blockingTransactions {@link Collection}
   */
  default void observeBlockOf(Transaction<E> blockedTransaction,
                              Collection<Transaction<E>> blockingTransactions) {}

  /**
   * Observe that {@code commited} has been commited, as all its operations have been executed.
   *
   * @param commited {@link Transaction}
   */
  default void observeCommit(Transaction<E> commited) {}

  /**
   * Observe that {@code step} has been undone during the abort of {@code forTransaction}.
   *
   * @param step {@link EChange}
   * @param forTransaction {@link Transaction}
   */
  default void observeUndo(EChange<E> step, Transaction<E> forTransaction) {}

  /**
   * Observe that a transaction is {@code aborting}, and may need to be executed again.
   *
   * @param aborting {@link Transaction}
   */
  default void observeAbort(Transaction<E> aborting) {}
}
