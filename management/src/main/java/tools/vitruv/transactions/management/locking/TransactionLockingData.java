package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.AbstractQueuedLongSynchronizer;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Represents information about a {@link TransactionState} within a lock manager.
 *
 * @param <E> - The data type of locking {@link TransactionState}s.
 */
@Data
class TransactionLockingData<E> {
  /**
   * Has the transaction started to release locks?
   * Transactions can only lock or unlock, so we prevent using default setters of Lombok.
   */
  @Setter(AccessLevel.NONE)
  private boolean unlocking = false;
  /**
   * Set of locks the transaction currently holds.
   */
  private final Set<Lock<E>> heldLocks = new HashSet<>();
  /**
   * Other transactions that currently block this transaction.
   */
  private final Set<TransactionState<E>> waitsFor = new HashSet<>();

  /**
   * Adds {@code lock} to the {@link TransactionLockingData#heldLocks}.
   *
   * @param lock - {@link Lock}
   */
  void registerLock(Lock<E> lock) {
    checkState(!unlocking, "Transaction must not acquire further locks when unlocking!");
    heldLocks.add(lock);
  }

  /**
   * Removes {@code lock} from the {@link TransactionLockingData#heldLocks}.
   * If requested, also marks the transaction as unlocking/shrinking.
   *
   * <p>Under some locking schemes, e.g. C2PL, a transaction may only acquire all locks at once,
   * or must not execute otherwise. We emulate such behavior by having a non-shrinking unregister
   * in case the transaction is blocked.
   *
   * <p>Crucially, after a shrinking/unlocking unregister, non-shrinking unregisters are not permitted.
   *
   * @param lock - {@link Lock}
   * @param shrinking - boolean
   */
  void unregisterLock(Lock<E> lock, boolean shrinking) {
    checkArgument(shrinking || !unlocking,
        "Must not release a lock in non-shrinking mode right now!");
    checkArgument(heldLocks.contains(lock), "Cannot release the lock for this transaction!");
    heldLocks.remove(lock);
    unlocking |= shrinking;
  }

  /**
   * Says that this transaction waits on {@code  otherTransactions}.
   *
   * @param otherTransactions - {@link Set}
   */
  void blockOn(Set<TransactionState<E>> otherTransactions) {
    waitsFor.addAll(otherTransactions);
  }

  /**
   * Unblocks this transaction for {@code unlockingTransaction}.
   *
   * @param unlockingTransactionState - {@link TransactionState}
   * @return Boolean
   *      true if and only if the managed transaction does not wait on other transactions.
   */
  boolean unblock(TransactionState<E> unlockingTransactionState) {
    return waitsFor.remove(unlockingTransactionState) && waitsFor.isEmpty();
  }
}
