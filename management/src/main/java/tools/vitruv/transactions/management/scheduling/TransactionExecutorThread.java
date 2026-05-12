package tools.vitruv.transactions.management.scheduling;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;

/**
 * A {@link TransactionExecutorThread} actually executes a transaction described in a
 * {@link TransactionState}, following some specific scheduler behavior.
 *
 * <p>TransactionExecutorThreads return their {@link TransactionStatus} as {@link Callable}s,
 * in order to control their execution (whether to retry or not,
 * what other transaction can run now, etc.)
 *
 * @param <E> Type of the elements that the {@link TransactionState} works on.
 */
public abstract class TransactionExecutorThread<E>
        implements Callable<TransactionExecutorThread.Result<E>> {
  /**
   * The transaction to actually execute.
   */
  protected final TransactionState<E> transactionState;
  /**
   * Observers that can be informed about transaction/scheduling events.
   */
  protected final ConcurrentLinkedDeque<SchedulingEventObserver<E>> observers;

  /**
   * Creates a new {@link TransactionExecutorThread}.
   *
   * @param transactionState {@link TransactionState}
   * @param observers {@link ConcurrentLinkedDeque}
   */
  public TransactionExecutorThread(TransactionState<E> transactionState,
                                   ConcurrentLinkedDeque<SchedulingEventObserver<E>> observers) {
    this.transactionState = transactionState;
    this.observers = observers;
  }

  /**
   * The execution {@link Result} of an execution. It describes:
   * <ol>
   *     <li>the execution status,</li>
   *     <li>what transactions are permitted to run now.</li>
   * </ol>
   *
   * @param status {@link TransactionStatus}
   * @param unblockedTransactions {@link Collection}
   * @param <E> Type of elements that the {@link TransactionState} runs on.
   */
  public record Result<E>(
          TransactionStatus status,
          Collection<TransactionState<E>> unblockedTransactions
  ) {}
}
