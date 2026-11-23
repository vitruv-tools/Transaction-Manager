package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.transactions.management.TransactionState;

/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * <p>This class holds what all locking protocols share: the lock table, the locks of each
 * transaction, and granting or refusing a single lock request. The protocols are its subclasses:
 * <ul>
 *   <li>{@link C2PLLockManager}: conservative 2PL with backoff; a blocked transaction releases its
 *   locks and is restarted once unblocked.</li>
 *   <li>{@link S2PLLockManager}: strict 2PL with deadlock detection; a transaction acquires
 *   further locks while it runs, waits for incompatible ones and keeps all of them until it
 *   ends.</li>
 * </ul>
 *
 * @param <E> - The Element type.
 */
public abstract class LockManager<E> {
  /**
   * Manages lock information.
   */
  private final ConcurrentMap<Lock<E>, LockData<E>> lockData = new ConcurrentHashMap<>();
  /**
   * Manages transaction information.
   */
  private final ConcurrentMap<TransactionState<E>, TransactionLockingData<E>> transactionData =
      new ConcurrentHashMap<>();

  /**
   * Submits a new {@link VitruviusChange} to run as transaction.
   *
   * @param change - {@link VitruviusChange}
   * @return {@link TransactionState}
   */
  public synchronized TransactionState<E> submitTransaction(VitruviusChange<E> change) {
    var newTransaction = new TransactionState<>(change);
    checkArgument(!transactionData.containsKey(newTransaction),
        "Attempting to submit a transaction twice!");
    transactionData.put(newTransaction, new TransactionLockingData<>());
    admitted(newTransaction);
    return newTransaction;
  }

  /**
   * Called when a transaction was submitted (also when it is resubmitted).
   *
   * @param transactionState - {@link TransactionState}
   */
  protected void admitted(TransactionState<E> transactionState) {
    // nothing by default
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
   * Whether {@code transactionState} is submitted and has not ended yet.
   *
   * @param transactionState - {@link TransactionState}
   * @return boolean
   */
  public synchronized boolean isActive(TransactionState<?> transactionState) {
    return transactionData.containsKey(transactionState);
  }

  /**
   * Acquires {@code lockToAcquire} for {@code transaction}.
   *
   * <p>The request succeeds if every other holder of the lock holds it in a mode compatible with
   * the requested one (see {@link LockMode#isCompatibleWith(LockMode)}). If the transaction
   * already holds the lock, it is converted to the least mode covering both (lock upgrade);
   * the transaction itself never blocks its own request.
   *
   * @param lockToAcquire - {@link Lock}
   * @param transactionState -  {@link TransactionState}
   * @return {@link Optional}
   *      The Optional type holds the other transactions that hold the lock in an incompatible
   *      mode and prevent its acquisition.
   */
  public synchronized Optional<Set<TransactionState<E>>> testLock(Lock<E> lockToAcquire,
                                                     TransactionState<E> transactionState)
      throws InterruptedException {
    var transactionStateData = transactionData.get(transactionState);
    checkArgument(transactionStateData != null, "Transaction is not currently active!");
    var data = lockData.get(lockToAcquire);

    // If no other transaction holds the lock, the request succeeds.
    if (data == null) {
      lockData.put(lockToAcquire, new LockData<>(lockToAcquire, transactionState));
      transactionStateData.registerLock(lockToAcquire);
      return Optional.empty();
    }

    var heldMode = data.holders().get(transactionState);
    var requestedMode = heldMode == null
        ? lockToAcquire.mode : LockMode.highestLockMode(heldMode, lockToAcquire.mode);
    var blockingTransactions = new HashSet<TransactionState<E>>();
    for (var holder : data.holders().entrySet()) {
      if (!holder.getKey().equals(transactionState)
          && !holder.getValue().isCompatibleWith(requestedMode)) {
        blockingTransactions.add(holder.getKey());
      }
    }
    if (!blockingTransactions.isEmpty()) {
      return Optional.of(blockingTransactions);
    }
    data.holders().put(transactionState, requestedMode);
    transactionStateData.registerLock(lockToAcquire.convert(requestedMode));
    return Optional.empty();
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
    lockData1.holders().remove(lockHolder);
    // If no transactions hold the lock, remove the lock as well
    if (lockData1.holders().isEmpty()) {
      lockData.remove(lock);
    }
    notifyAll();
  }

  /**
   * Releases all locks held by {@code transactionState}.
   *
   * @param transactionState - {@link TransactionState}
   * @param shrinking {@link Boolean}
   *     If {@code shrinking == false}, then the associated {@code transactionState}
   *     may acquire new locks at some later point in time.
   *     This is required to restart a blocked or aborted transaction.
   */
  public synchronized void releaseAllLocks(TransactionState<E> transactionState,
                                           boolean shrinking) throws InterruptedException {
    for (var lock : getLocksHeldBy(transactionState)) {
      releaseLock(lock, transactionState, shrinking);
    }
  }

  /**
   * The locking data of an active transaction.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link TransactionLockingData}
   */
  protected TransactionLockingData<E> lockingData(TransactionState<E> transactionState) {
    var data = transactionData.get(transactionState);
    checkArgument(data != null, "Transaction is not currently active!");
    return data;
  }

  /**
   * Removes {@code transactionState} and returns the transactions that waited only for it.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link List}
   */
  protected synchronized List<TransactionState<E>> unblockTransactionsBlockedBy(
      TransactionState<E> transactionState) {
    // Cleanup
    transactionData.remove(transactionState);
    // Collect unblocked transactions
    var unblockedTransactions = new ArrayList<TransactionState<E>>();
    for (var transactionEntry: transactionData.entrySet()) {
      if (transactionEntry.getValue().unblock(transactionState)) {
        unblockedTransactions.add(transactionEntry.getKey());
      }
    }
    return unblockedTransactions;
  }

  /**
   * Checks that {@code transactionState} holds no locks.
   *
   * @param transactionState - {@link TransactionState}
   */
  protected synchronized void checkForReleaseOfAllLocks(TransactionState<E> transactionState) {
    var data = transactionData.get(transactionState);
    checkArgument(data.getHeldLocks().isEmpty(),
        "Only transactions that do not have locks can be commited!");
  }
}
