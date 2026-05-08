package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashSet;
import java.util.Set;
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
   * Set of locks  the transaction currently holds.
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
   * Removes {@code lock} from the {@link TransactionLockingData#heldLocks}, and marks this
   * transaction as unlocking/shrinking.
   *
   * @param lock - {@link Lock}
   */
  void unregisterLock(Lock<E> lock) {
    checkArgument(heldLocks.contains(lock), "Cannot release the lock for this transaction!");
    heldLocks.remove(lock);
    unlocking = true;
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
    waitsFor.remove(unlockingTransactionState);
    return waitsFor.isEmpty();
  }
}
