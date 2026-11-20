package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.locking.deadlock.DeadlockDetector;
import tools.vitruv.transactions.management.locking.deadlock.DeadlockVictimException;
import tools.vitruv.transactions.management.locking.deadlock.VictimSelectionStrategy;

/**
 * Strict two-phase locking with deadlock detection: a transaction acquires further locks while
 * it runs (e.g. per propagation step), waits while a lock is held in an incompatible mode, and
 * keeps all locks until {@link #endTransaction(TransactionState, boolean)}. Waiting transactions
 * form a wait-for graph; a cycle is a deadlock, and a {@link VictimSelectionStrategy} chooses the
 * transaction to abort ({@link DeadlockVictimException}).
 *
 * <p>The locks of a change come from a {@link LockProvider}, by default instance locks only
 * ({@link ModelLevelLockProvider}); with a {@link MetamodelLevelLockProvider} also the
 * metamodel-level locks of what propagating the change may access.
 *
 * @param <E> - The Element type.
 */
public class S2PLLockManager<E> extends LockManager<E> {
  private final LockProvider<E> lockProvider;
  private final DeadlockDetector<E> deadlockDetector;

  /**
   * Creates a lock manager with instance locks and youngest-dies victim selection.
   */
  public S2PLLockManager() {
    this(new ModelLevelLockProvider<>(), VictimSelectionStrategy.youngestDies());
  }

  /**
   * Creates a new {@link S2PLLockManager}.
   *
   * @param lockProvider - computes the locks of a change, see {@link #acquireLocksFor}
   * @param victimSelection - chooses the victim of a deadlock
   */
  public S2PLLockManager(LockProvider<E> lockProvider,
      VictimSelectionStrategy<E> victimSelection) {
    this.lockProvider = lockProvider;
    this.deadlockDetector = new DeadlockDetector<>(victimSelection);
  }

  @Override
  protected void admitted(TransactionState<E> transactionState) {
    deadlockDetector.admit(transactionState);
  }

  /**
   * Acquires the locks that the {@link LockProvider} computes for {@code change}, see
   * {@link #acquireLocks(TransactionState, Collection)}.
   *
   * @param transactionState - {@link TransactionState}
   * @param change - {@link EChange}
   * @throws DeadlockVictimException if {@code transactionState} was chosen as deadlock victim
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public void acquireLocksFor(TransactionState<E> transactionState, EChange<E> change)
      throws DeadlockVictimException, InterruptedException {
    acquireLocks(transactionState, lockProvider.locksFor(change));
  }

  /**
   * Acquires all {@code locks} (instance or metamodel level) for {@code transactionState},
   * waiting while another transaction holds one of them in an incompatible mode. Locks acquired
   * here are kept until {@link #endTransaction(TransactionState, boolean)}, also those acquired
   * before a wait.
   *
   * <p>While waiting, the transaction's wait-for edges point to the blocking transactions. If a
   * new edge closes a cycle, the {@link VictimSelectionStrategy} chooses a victim: if that is
   * {@code transactionState}, this call throws {@link DeadlockVictimException}; otherwise the
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
    var data = lockingData(transactionState);
    checkArgument(!data.isUnlocking(),
        "Transaction has started to unlock; it must not acquire further locks!");
    var pending = new ArrayList<Lock<E>>(locks);
    while (true) {
      deadlockDetector.throwIfVictim(transactionState);
      var blockers = new HashSet<TransactionState<E>>();
      for (var iterator = pending.iterator(); iterator.hasNext(); ) {
        var blocking = testLock(iterator.next(), transactionState);
        if (blocking.isPresent()) {
          blockers.addAll(blocking.get());
        } else {
          iterator.remove();
        }
      }
      var victim = deadlockDetector.waitFor(transactionState, blockers);
      if (blockers.isEmpty()) {
        return;
      }
      if (victim.isPresent()) {
        deadlockDetector.throwIfVictim(transactionState);
        notifyAll(); // wake the victim, which waits in this method
      }
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
    return deadlockDetector.waitsFor(transactionState);
  }

  /**
   * Searches the wait-for graph for a cycle through {@code transactionState}.
   *
   * @param transactionState - {@link TransactionState}
   * @return the transactions on the cycle, starting with {@code transactionState}, or empty
   */
  public synchronized Optional<List<TransactionState<E>>> findWaitForCycle(
      TransactionState<E> transactionState) {
    return deadlockDetector.findCycle(transactionState);
  }

  /**
   * Ends {@code transactionState} after it committed or after its changes were undone (abort,
   * e.g. as deadlock victim). Releases all its locks, removes it from the lock manager and wakes
   * waiting transactions. The status of the {@link TransactionState} is left to the caller.
   *
   * @param transactionState - {@link TransactionState}
   * @param committed - false if the transaction was aborted and will be resubmitted; it then keeps
   *                  its age for victim selection (see {@link #cancelTransaction} otherwise)
   * @return the transactions that waited for {@code transactionState} only
   */
  public synchronized Collection<TransactionState<E>> endTransaction(
      TransactionState<E> transactionState, boolean committed) throws InterruptedException {
    return end(transactionState, !committed);
  }

  /**
   * Ends an aborted {@code transactionState} that will not be resubmitted: like
   * {@link #endTransaction(TransactionState, boolean)} with {@code committed = false}, but its age
   * is forgotten too.
   *
   * @param transactionState - {@link TransactionState}
   * @return the transactions that waited for {@code transactionState} only
   */
  public synchronized Collection<TransactionState<E>> cancelTransaction(
      TransactionState<E> transactionState) throws InterruptedException {
    return end(transactionState, false);
  }

  /**
   * Whether the lock manager still keeps the age of {@code transactionState} (it was submitted
   * and has neither committed nor been cancelled).
   *
   * @param transactionState - {@link TransactionState}
   * @return boolean
   */
  public synchronized boolean keepsAgeOf(TransactionState<E> transactionState) {
    return deadlockDetector.knows(transactionState);
  }

  private Collection<TransactionState<E>> end(TransactionState<E> transactionState,
      boolean mayBeResubmitted) throws InterruptedException {
    checkArgument(isActive(transactionState), "Transaction is not currently active!");
    releaseAllLocks(transactionState, true);
    deadlockDetector.end(transactionState, mayBeResubmitted);
    var unblocked = unblockTransactionsBlockedBy(transactionState);
    notifyAll();
    return unblocked;
  }
}
