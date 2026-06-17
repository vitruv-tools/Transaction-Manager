package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import tools.vitruv.transactions.management.scheduling.SchedulingEventObserver;

/**
 * {@code TransactionStatusTracker}
 * tracks what transactions are currently running (Active), commited, or aborted.
 *
 * @param <E> Element type that {@link TransactionState}s modify.
 */
public class TransactionStatusTracker<E> implements SchedulingEventObserver<E> {
  @Getter
  private final List<TransactionState<E>> activeTransactions = new ArrayList<>();
  @Getter
  private final List<TransactionState<E>> commitedTransactions = new ArrayList<>();
  @Getter
  private final List<TransactionState<E>> abortedTransactions = new ArrayList<>();

  /**
   * Clears the state of observed transactions.
   */
  public void clear() {
    activeTransactions.clear();
    commitedTransactions.clear();
    abortedTransactions.clear();
  }

  @Override
  public void observeBlockOf(TransactionState<E> blocked,
                             Collection<TransactionState<E>> blocking) {
    System.out.println("Transaction " + blocked + " is blocked!");
  }

  @Override
  public void observeRunning(TransactionState<E> running) {
    activeTransactions.add(running);
  }

  @Override
  public void observeAbort(TransactionState<E> aborting) {
    assertTrue(activeTransactions.remove(aborting),
        "Trying to abort a transaction that is not running!");
    abortedTransactions.add(aborting);
    System.out.println("Aborted transaction " + aborting);
  }

  @Override
  public void observeCommit(TransactionState<E> commited) {
    assertTrue(activeTransactions.remove(commited),
        "Trying to commit a transaction that is not running!");
    commitedTransactions.add(commited);
    System.out.println("Commited transaction " + commited);
  }
}
