package tools.vitruv.transactions.management.locking;

import java.util.HashMap;
import java.util.Map;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Represents information about a {@link Lock} within a lock manager.
 *
 * @param <E> The data type of locking {@link TransactionState}s.
 * @param holders The transactions that hold this lock at present, each with the mode it holds
 *                the lock in (several holders are possible for compatible modes).
 */
record LockData<E>(
    Map<TransactionState<E>, LockMode> holders
) {
  /**
   * Creates lockData for {@code firstHolder}.
   *
   * @param newLock - {@link Lock}
   * @param firstHolder - {@link TransactionState}
   */
  LockData(Lock<E> newLock, TransactionState<E> firstHolder) {
    this(new HashMap<>());
    holders.put(firstHolder, newLock.mode);
  }
}
