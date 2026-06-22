package tools.vitruv.transactions.management;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.transactions.management.scheduling.SchedulingEventObserver;

/**
 * {@code TransactionStatusTracker}
 * tracks what transactions are currently running (Active), commited, or aborted.
 *
 * @param <E> Element type that {@link TransactionState}s modify.
 */
public class TransactionStatusTracker<E> implements SchedulingEventObserver<E> {
  private static final Logger LOGGER = LogManager.getLogger(TransactionStatusTracker.class);

  @Getter
  private final ConcurrentMap<TransactionState<E>, Boolean> activeTransactions = new ConcurrentHashMap<>();
  @Getter
  private final ConcurrentMap<TransactionState<E>, Boolean> commitedTransactions = new ConcurrentHashMap<>();
  @Getter
  private final ConcurrentMap<TransactionState<E>, Boolean> abortedTransactions = new ConcurrentHashMap<>();

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
    LOGGER.info("Transaction {} is blocked!", blocked);
  }

  @Override
  public void observeRunning(TransactionState<E> running) {
    activeTransactions.put(running, true);
  }

  @Override
  public void observeAbort(TransactionState<E> aborting) {
    checkState(activeTransactions.remove(aborting),
        "Trying to abort a transaction that is not running!");
    abortedTransactions.put(aborting, true);
    LOGGER.info("Aborted transaction {}", aborting);
  }

  @Override
  public void observeCommit(TransactionState<E> commited) {
    checkState(activeTransactions.remove(commited),
        "Trying to commit a transaction that is not running!");
    commitedTransactions.put(commited, true);
    LOGGER.info("Commited transaction {}", commited);
  }
}
