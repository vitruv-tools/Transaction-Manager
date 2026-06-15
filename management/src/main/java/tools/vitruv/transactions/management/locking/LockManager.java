package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.swing.text.html.Option;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;


/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * @param <E> - The Element type.
 */
public class LockManager<E> {
  /**
   * An "in-progress" map.
   *
   * <p>{@code inProgress(lock).get == true} means that a thread is currently trying to acquire or
   * release a lock. Only one thread may be allowed to do so at a time.
   *
   * <p>Before each access to a lock in {@code LockManager#lockData},
   * a thread must request access with {@code ConcurrentMap#putIfAbsent},
   * or else, wait on the mapped value.
   * After each access, a thread must notify waiting threads,
   * and then release this lock with {@code ConcurrentMap#remove}.
   */
  private final ConcurrentMap<Lock<E>, Lock<E>> inProgress = new ConcurrentHashMap<>();
  /**
   * Manages lock information.
   */
  private final Map<Lock<E>, LockData<E>> lockData = new HashMap<>();
  /**
   * Manages transaction information.
   */
  private final Map<TransactionState<E>, TransactionLockingData<E>> transactionData = new HashMap<>();

  /**
   * Submits a new {@link VitruviusChange} to run as transaction.
   *
   * @param change - {@link VitruviusChange}
   */
  public synchronized TransactionState<E> submitTransaction(VitruviusChange<E> change) {
    var newTransaction = new TransactionState<>(change);
    transactionData.put(newTransaction, new TransactionLockingData<>());
    return newTransaction;
  }

  /**
   * Returns all locks that {@code transaction} holds.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link Set}
   */
  public synchronized Set<Lock<E>> getLocksHeldBy(TransactionState<E> transactionState) {
    checkArgument(transactionData.containsKey(transactionState),
        "The transaction is not currently active!");
    return Set.copyOf(transactionData.get(transactionState).getHeldLocks());
  }

  /**
   * Attempts to acquire all locks that the next {@link EChange} of {@code transaction} requires.
   * If the lock request fails, returns the set of other transactions blocking {@code transaction}.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link Optional}
   */
  public synchronized Optional<Set<TransactionState<E>>> acquireLocksForNextOperation(
      TransactionState<E> transactionState)
    throws InterruptedException {
    var data = transactionData.get(transactionState);
    checkArgument(data != null, "Transactions is not being processed!");
    checkArgument(!data.isUnlocking(),
        "Transaction has started to unlock; it must not acquire further locks!");
    checkArgument(transactionState.getStatus() == TransactionStatus.RUNNING,
        "Cannot acquire locks if the transaction is not running!");
    // Peek operation
    var operation = transactionState.peekNextOperationForExecutionChecking();
    // Compute locks
    var locksToAcquire = LockComputer.computeLocksFor(operation);

    // Identify all blocking transactions.
    var locksAcquired = new ArrayList<Lock<E>>();

    for (var lock : locksToAcquire) {
      var blockingTransactions = testLock(lock, transactionState);
      // No block -> mark lock as acquired
      if (blockingTransactions.isEmpty()) {
        locksAcquired.add(lock);
      } else {
        // Mark transaction as blocked
        data.blockOn(blockingTransactions.get());
        transactionState.setToBlocked();
        // Release partial locks
        for (var wronglyAcquiredLock : locksAcquired) {
          releaseLock(wronglyAcquiredLock, transactionState, false);
        }
        return blockingTransactions;
      }
    }

    transactionState.markNextOperationAsExecutable();
    return Optional.empty();
  }

  /**
   * Acquires {@code lockToAcquire} for {@code transaction}.
   *
   * @param lockToAcquire - {@link Lock}
   * @param transactionState -  {@link TransactionState}
   * @return {@link Optional}
   *      The Optional type holds another transaction that already has the lock,
   *      and prevents its acquisition.
   */
  public synchronized Optional<Set<TransactionState<E>>> testLock(Lock<E> lockToAcquire,
                                                     TransactionState<E> transactionState)
      throws InterruptedException {

    var data = lockData.putIfAbsent(lockToAcquire, new LockData<>(lockToAcquire, transactionState));
    var transactionStateData = transactionData.get(transactionState);

    // Return value
    Optional<Set<TransactionState<E>>> otherLockHolders = Optional.empty();

    // If no other transaction holds the lock, the request succeeds.
    if (data == null) {
      lockData.put(lockToAcquire, new LockData<>(lockToAcquire, transactionState));
      transactionStateData.registerLock(lockToAcquire);
    } else {
      var holdingTransactions = data.getHolders();
      // If only the current transaction holds the lock, the request also succeeds.
      // Convert the lock, if required.

      if (holdingTransactions.size() == 1 && holdingTransactions.contains(transactionState)) {
        var currentLockMode = data.getMode();
        var requestedLockMode = LockMode.highestLockMode(currentLockMode, lockToAcquire.getMode());
        var upgradedLock = lockToAcquire.convert(requestedLockMode);
        lockData.put(upgradedLock, new LockData<>(upgradedLock, transactionState));
        transactionStateData.registerLock(lockToAcquire);
      } else if (lockToAcquire.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE
          && data.getMode() == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
        holdingTransactions.add(transactionState);
        transactionStateData.registerLock(lockToAcquire);
      }
      // Return all locking transactions.
      else if (!holdingTransactions.isEmpty()) {
        otherLockHolders = Optional.of(holdingTransactions);
      }
    }
    return otherLockHolders;
  }

  /**
   * Releases {@code lock} if it held by the {@code lockHolder} transaction.
   *
   * @param lock - {@link Lock}
   * @param lockHolder - {@link TransactionState}
   * @param shrinking - {@link Boolean}
   */
  public synchronized void releaseLock(Lock<E> lock, TransactionState<E> lockHolder,
                                       boolean shrinking)
      throws InterruptedException {
    // Transaction must be registered
    var data = transactionData.get(lockHolder);
    checkArgument(data != null, "Transaction is not currently active!");
    var locks = data.getHeldLocks();
    checkArgument(locks.contains(lock), "Transaction does not hold the lock!");

    // Update locking information
    data.unregisterLock(lock, shrinking);
    // Remove lockHolder
    var lockData1 = lockData.get(lock);
    lockData1.getHolders().remove(lockHolder);
    // If no transactions hold the lock, remove the lock as well
    if (lockData1.getHolders().isEmpty()) {
      lockData.remove(lock);
    }

    // Mark lock as processed
    inProgress.remove(lock);
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
    // Mark commit
    transactionState.setToCommited();
    return unblockTransactionsBlockedBy(transactionState);
  }

  private List<TransactionState<E>> unblockTransactionsBlockedBy(TransactionState<E> transactionState) {
    // Cleanup
    transactionData.remove(transactionState);
    // Collect unblocked transactions
    return transactionData
        .entrySet()
        .stream()
        .filter(entry ->
          entry.getKey().getStatus() == TransactionStatus.BLOCKED
                  && entry.getValue().unblock(transactionState))
        .map(Map.Entry::getKey)
        .toList();
  }

  private synchronized void checkForReleaseOfAllLocks(TransactionState<E> transactionState) {
    var data = transactionData.get(transactionState);
    checkArgument(data.getHeldLocks().isEmpty(),
        "Only transactions that do not have locks can be commited!");
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
    // Mark abort
    transactionState.setToAborted();
    return unblockTransactionsBlockedBy(transactionState);
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
    return LockComputer.computeLocksFor(nextOperation);
  }

}
