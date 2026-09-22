package tools.vitruv.transactions.management.locking;

import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Represents information about a {@link Lock} within a lock manager.
 *
 * @param <E> The data type of locking {@link TransactionState}s.
 * @param holders Holders, the transactions hold this lock at present.
 * @param mode Lock mode, whether the lock is shared, or exclusive.
 */
record LockData<E>(
    Set<TransactionState<E>> holders,
    LockMode mode
) {
  /**
   * Creates lockData for {@code firstHolder}.
   *
   * @param newLock - {@link Lock}
   * @param firstHolder - {@link TransactionState}
   */
  LockData(Lock<E> newLock, TransactionState<E> firstHolder) {
    this(new HashSet<>(), newLock.mode);
    holders.add(firstHolder);
  }
}
