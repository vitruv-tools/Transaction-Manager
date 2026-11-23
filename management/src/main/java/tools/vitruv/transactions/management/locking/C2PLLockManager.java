package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;

/**
 * Conservative two-phase locking with backoff: before an operation of a transaction is
 * executed, all locks it requires (computed by the {@link ModelLevelLockProvider}) are requested;
 * if one is held incompatibly, the transaction is marked as blocked by the holders and, with
 * backoff, releases its locks, to be restarted once it is unblocked.
 *
 * @param <E> - The Element type.
 */
public class C2PLLockManager<E> extends LockManager<E> {
  private final LockProvider<E> lockProvider = new ModelLevelLockProvider<>();

  /**
   * Attempts to acquire all locks that the next {@link EChange} of {@code transaction} requires.
   * If the lock request fails, returns the set of other transactions blocking {@code transaction}.
   *
   * @param transactionState - {@link TransactionState}
   * @param backoff - boolean
   *                If {@code backoff} is set to true, release all held locks for
   *                {@code transactionState}.
   *                Used to support conservative Two-Phase Locking.
   *
   * @return {@link Optional}
   */
  public synchronized Optional<Set<TransactionState<E>>> acquireLocksForNextOperation(
      TransactionState<E> transactionState,
      boolean backoff)
      throws InterruptedException {
    var data = lockingData(transactionState);
    checkArgument(!data.isUnlocking(),
        "Transaction has started to unlock; it must not acquire further locks!");
    checkArgument(transactionState.getStatus() == TransactionStatus.RUNNING,
        "Cannot acquire locks if the transaction is not running!");
    // Peek operation
    var operation = transactionState.peekNextOperationForExecutionChecking();
    // Compute locks
    var locksToAcquire = lockProvider.locksFor(operation);

    for (var lock : locksToAcquire) {
      var blockingTransactions = testLock(lock, transactionState);
      if (blockingTransactions.isPresent()) {
        // Mark transaction as blocked
        for (var blockingTransaction : blockingTransactions.get()) {
          data.blockOn(blockingTransaction);
        }
        // Release partial locks
        if (backoff) {
          releaseAllLocks(transactionState, false);
        }
        return blockingTransactions;
      }
    }

    transactionState.markNextOperationAsExecutable();
    return Optional.empty();
  }

  /**
   * Commits {@code transaction}, assuming that all its operations have been executed,
   * and all its locks have been released.
   *
   * <p>Upon that point, we update the {@code waitsForGraph} relation, and return all
   * transactions that are now unblocked.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link Collection}
   */
  public synchronized Collection<TransactionState<E>> commit(TransactionState<E> transactionState) {
    checkArgument(transactionState.getStatus() == TransactionStatus.RUNNING,
        "Only running transactions can be committed!");
    checkArgument(!transactionState.wantsToAcquireLocks(),
        "Only transactions that have no more operations can be committed!");
    checkForReleaseOfAllLocks(transactionState);
    var unblocked = unblockTransactionsBlockedBy(transactionState);
    notifyAll();
    return unblocked;
  }

  /**
   * Aborts an aborting {@code transaction}, assuming that all its executed operations
   * have been inverted and all its locks have been released.
   *
   * <p>Upon that point, we update the {@code waitsForGraph} relation, and return all
   * transactions that are now unblocked.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link Collection}
   */
  public synchronized Collection<TransactionState<E>> abort(TransactionState<E> transactionState) {
    checkArgument(transactionState.getStatus() == TransactionStatus.ABORTING,
        "Only aborting transactions may be aborted!");
    checkArgument(!transactionState.hasOperationsToInvert(),
        "Transaction still has operations to invert!");
    checkForReleaseOfAllLocks(transactionState);
    var unblocked = unblockTransactionsBlockedBy(transactionState);
    notifyAll();
    return unblocked;
  }

  /**
   * Computes locks for the next operation of {@code transaction},
   * which must be running at this point.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link List}
   */
  public List<Lock<E>> computeNextLocksFor(TransactionState<E> transactionState) {
    var nextOperation = transactionState.peekNextOperationForExecutionChecking();
    return lockProvider.locksFor(nextOperation);
  }
}
