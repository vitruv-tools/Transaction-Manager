package tools.vitruv.transactions.management.scheduling;

import java.util.concurrent.Callable;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;

/**
 * A {@link TransactionExecutorThread} actually executes a transaction described in a
 * {@link TransactionState}, following some specific scheduler behavior.
 *
 * <p>TransactionExecutorThreads return their {@link TransactionStatus} as {@link Callable}s,
 * in order to manage their execution.
 *
 * @param <E> Type of the elements that the {@link TransactionState} works on.
 */
public abstract class TransactionExecutorThread<E> implements Callable<TransactionStatus> {
  /**
   * The transaction to actually execute.
   */
  protected final TransactionState<E> transactionState;

  /**
   * Creates a new {@link TransactionExecutorThread}.
   *
   * @param transactionState {@link TransactionState}
   */
  public TransactionExecutorThread(TransactionState<E> transactionState) {
    this.transactionState = transactionState;
  }
}
