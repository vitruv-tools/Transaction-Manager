package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * {@code TransactionStatusTracker}
 * tracks what transactions are currently running (Active), commited, or aborted.
 *
 * @param <E> Element type that {@link Transaction}s modify.
 */
public class TransactionStatusTracker<E> implements SchedulingEventObserver<E> {
  @Getter
  private final List<Transaction<E>> activeTransactions = new ArrayList<>();
  @Getter
  private final List<Transaction<E>> commitedTransactions = new ArrayList<>();
  @Getter
  private final List<Transaction<E>> abortedTransactions = new ArrayList<>();

  /**
   * Clears the state of observed transactions.
   */
  public void clear() {
    activeTransactions.clear();
    commitedTransactions.clear();
    abortedTransactions.clear();
  }

  @Override
  public void observeRunning(Transaction<E> running) {
    activeTransactions.add(running);
  }

  @Override
  public void observeAbort(Transaction<E> aborting) {
    assertTrue(activeTransactions.remove(aborting),
        "Trying to abort a transaction that is not running!");
    abortedTransactions.add(aborting);
  }

  @Override
  public void observeCommit(Transaction<E> commited) {
    assertTrue(activeTransactions.remove(commited),
        "Trying to commit a transaction that is not running!");
    commitedTransactions.add(commited);
  }
}
