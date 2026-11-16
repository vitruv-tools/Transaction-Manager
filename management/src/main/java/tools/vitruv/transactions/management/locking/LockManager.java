package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;


/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * <p>Two protocols are supported:
 * <ul>
 *   <li>Conservative 2PL with backoff ({@link #acquireLocksForNextOperation(TransactionState,
 *   boolean)}): a blocked transaction releases its locks and is restarted once unblocked.</li>
 *   <li>Strict 2PL with deadlock detection ({@link #acquireLocks(TransactionState, Collection)}):
 *   a transaction acquires further locks while it runs (e.g. per propagation step, before each
 *   reaction), waits while a lock is held in an incompatible mode, and keeps all locks until
 *   {@link #endTransaction(TransactionState, boolean)}. Waiting transactions form a wait-for
 *   graph; a cycle is a deadlock, and the youngest transaction of the cycle is chosen as victim
 *   ({@link DeadlockVictimException}). A resubmitted transaction keeps its age.</li>
 * </ul>
 *
 * @param <E> - The Element type.
 */
public class LockManager<E> {
  /**
   * Manages lock information.
   */
  private final ConcurrentMap<Lock<E>, LockData<E>> lockData = new ConcurrentHashMap<>();
  /**
   * Manages transaction information.
   */
  private final ConcurrentMap<TransactionState<E>, TransactionLockingData<E>> transactionData = new ConcurrentHashMap<>();
  /**
   * Age of each transaction (lower = older), by the order of first submission. Survives aborts,
   * because a resubmitted transaction is equal to its aborted predecessor (same change), so a
   * restarted deadlock victim eventually becomes the oldest one and is no longer chosen.
   */
  private final Map<TransactionState<E>, Long> admissionOrder = new HashMap<>();
  private long nextAdmission = 0;
  /**
   * Transactions chosen as deadlock victims that have not noticed it yet.
   */
  private final Set<TransactionState<E>> deadlockVictims = new HashSet<>();

  /**
   * Submits a new {@link VitruviusChange} to run as transaction.
   *
   * @param change - {@link VitruviusChange}
   */
  public synchronized TransactionState<E> submitTransaction(VitruviusChange<E> change) {
    var newTransaction = new TransactionState<>(change);
    checkArgument(!transactionData.containsKey(newTransaction), "Attempting to submit a transaction twice!");
    transactionData.put(newTransaction, new TransactionLockingData<>());
    admissionOrder.putIfAbsent(newTransaction, nextAdmission++);
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
   * Strict 2PL: acquires all {@code locks} for {@code transactionState}, waiting while another
   * transaction holds one of them in an incompatible mode. Locks acquired here are kept until
   * {@link #endTransaction(TransactionState, boolean)}, also those acquired before a wait.
   *
   * <p>While waiting, the transaction's wait-for edges point to the blocking transactions. If a
   * new edge closes a cycle, the youngest transaction of the cycle becomes the victim: if that
   * is {@code transactionState}, this call throws {@link DeadlockVictimException}; otherwise the
   * victim's own waiting call throws it. A victim must undo its changes and end with
   * {@code committed = false}, which releases its locks and wakes the waiting transactions.
   *
   * @param transactionState - {@link TransactionState}
   * @param locks - {@link Collection}
   * @throws DeadlockVictimException if {@code transactionState} was chosen as deadlock victim
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public synchronized void acquireLocks(TransactionState<E> transactionState,
                                        Collection<? extends Lock<E>> locks)
      throws DeadlockVictimException, InterruptedException {
    var data = transactionData.get(transactionState);
    checkArgument(data != null, "Transaction is not currently active!");
    checkArgument(!data.isUnlocking(),
        "Transaction has started to unlock; it must not acquire further locks!");
    var pending = new ArrayList<Lock<E>>(locks);
    while (true) {
      throwIfVictim(transactionState, data);
      var blockers = new HashSet<TransactionState<E>>();
      for (var iterator = pending.iterator(); iterator.hasNext(); ) {
        var blocking = testLock(iterator.next(), transactionState);
        if (blocking.isPresent()) {
          blockers.addAll(blocking.get());
        } else {
          iterator.remove();
        }
      }
      data.waitOnlyFor(blockers);
      if (blockers.isEmpty()) {
        return;
      }
      var cycle = findWaitForCycle(transactionState);
      if (cycle.isPresent()) {
        var victim = youngest(cycle.get());
        deadlockVictims.add(victim);
        throwIfVictim(transactionState, data);
        notifyAll();
      }
      wait();
    }
  }

  /**
   * Waits until none of {@code transactions} is active any more (committed or aborted). A
   * deadlock victim calls this before it restarts, so that it does not grab its locks again before
   * the transactions it deadlocked with could finish (which would recreate the same deadlock).
   * The caller must not hold locks.
   *
   * @param transactions - {@link Collection}
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public synchronized void awaitEnd(Collection<? extends TransactionState<?>> transactions)
      throws InterruptedException {
    while (transactions.stream().anyMatch(transactionData::containsKey)) {
      wait();
    }
  }

  /**
   * The transactions that {@code transactionState} currently waits for (its wait-for edges).
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link Set}, empty if it does not wait or is not active
   */
  public synchronized Set<TransactionState<E>> getWaitsFor(TransactionState<E> transactionState) {
    var data = transactionData.get(transactionState);
    return data == null ? Set.of() : data.waitsForSnapshot();
  }

  private void throwIfVictim(TransactionState<E> transactionState,
                             TransactionLockingData<E> data) throws DeadlockVictimException {
    if (deadlockVictims.remove(transactionState)) {
      var cycle = findWaitForCycle(transactionState).orElse(List.of(transactionState));
      data.waitOnlyFor(List.of());
      throw new DeadlockVictimException(cycle);
    }
  }

  private TransactionState<E> youngest(List<TransactionState<E>> transactions) {
    return transactions.stream()
        .max(Comparator.comparingLong(t -> admissionOrder.getOrDefault(t, Long.MAX_VALUE)))
        .orElseThrow();
  }

  /**
   * Searches the wait-for graph for a cycle through {@code transactionState}.
   *
   * @param transactionState - {@link TransactionState}
   * @return the transactions on the cycle, starting with {@code transactionState}, or empty
   */
  public synchronized Optional<List<TransactionState<E>>> findWaitForCycle(
      TransactionState<E> transactionState) {
    // depth-first search for a path from the successors of transactionState back to it
    Map<TransactionState<E>, TransactionState<E>> predecessor = new HashMap<>();
    Deque<TransactionState<E>> stack = new ArrayDeque<>();
    stack.push(transactionState);
    Set<TransactionState<E>> visited = new HashSet<>();
    while (!stack.isEmpty()) {
      var current = stack.pop();
      if (!visited.add(current)) {
        continue;
      }
      var currentData = transactionData.get(current);
      if (currentData == null) {
        continue;
      }
      for (var next : currentData.waitsForSnapshot()) {
        if (next.equals(transactionState)) {
          var cycle = new ArrayList<TransactionState<E>>();
          for (var t = current; t != null; t = predecessor.get(t)) {
            cycle.add(0, t);
          }
          return Optional.of(cycle);
        }
        if (!visited.contains(next)) {
          predecessor.putIfAbsent(next, current);
          stack.push(next);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Strict 2PL: ends {@code transactionState} after it committed or after its changes were
   * undone (abort, e.g. as deadlock victim). Releases all its locks, removes it from the lock
   * manager and wakes waiting transactions. The status of the {@link TransactionState} is left to
   * the caller.
   *
   * @param transactionState - {@link TransactionState}
   * @param committed - false if the transaction was aborted and may be resubmitted; it then keeps
   *                  its age for victim selection
   * @return the transactions that waited for {@code transactionState} only
   */
  public synchronized Collection<TransactionState<E>> endTransaction(
      TransactionState<E> transactionState, boolean committed) throws InterruptedException {
    checkArgument(transactionData.containsKey(transactionState),
        "Transaction is not currently active!");
    releaseAllLocks(transactionState, true);
    deadlockVictims.remove(transactionState);
    if (committed) {
      admissionOrder.remove(transactionState);
    }
    var unblocked = unblockTransactionsBlockedBy(transactionState);
    notifyAll();
    return unblocked;
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
    admissionOrder.remove(transactionState);
    var unblocked = unblockTransactionsBlockedBy(transactionState);
    notifyAll();
    return unblocked;
  }

  private synchronized List<TransactionState<E>> unblockTransactionsBlockedBy(
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
    deadlockVictims.remove(transactionState);
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
    return LockComputer.computeLocksFor(nextOperation);
  }

}
