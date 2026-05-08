package tools.vitruv.transactions.management.scheduling;

import java.util.Collection;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.transactions.management.TransactionState;

/**
 * An observer to transaction execution events.
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
   * @param newTransaction {@link TransactionState}
   */
  default void observeAdmission(TransactionState<E> newTransaction) {}

  /**
   * Observe that {@code running} can now start to execute operations.
   *
   * @param running {@link TransactionState}
   */
  default void observeRunning(TransactionState<E> running) {}

  /**
   * Observe that {@code forTransaction} has executed {@code step}.
   *
   * @param step {@link EChange}
   * @param forTransaction {@link TransactionState}
   */
  default void observeExecutionOf(EChange<E> step, TransactionState<E> forTransaction) {}

  /**
   * Observe that {@code blockedTransaction} is blocked by the set of {@code blockingTransactions},
   * and is waiting for their further execution.
   *
   * @param blockedTransaction {@link TransactionState}
   * @param blockingTransactions {@link Collection}
   */
  default void observeBlockOf(TransactionState<E> blockedTransaction,
                              Collection<TransactionState<E>> blockingTransactions) {}

  /**
   * Observe that {@code commited} has been commited, as all its operations have been executed.
   *
   * @param commited {@link TransactionState}
   */
  default void observeCommit(TransactionState<E> commited) {}

  /**
   * Observe that {@code step} has been undone during the abort of {@code forTransaction}.
   *
   * @param step {@link EChange}
   * @param forTransaction {@link TransactionState}
   */
  default void observeUndo(EChange<E> step, TransactionState<E> forTransaction) {}

  /**
   * Observe that a transaction is {@code aborting}, and may need to be executed again.
   *
   * @param aborting {@link TransactionState}
   */
  default void observeAbort(TransactionState<E> aborting) {}
}
