package tools.vitruv.transactions.management.locking;

import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import tools.vitruv.transactions.management.Transaction;

/**
 * Represents information about a {@link Lock} within a lock manager.
 *
 * @param <E> - The data type of locking {@link Transaction}s.
 */
@Data
class LockData<E> {
  /**
   * Holders, the transactions hold this lock at present.
   */
  private final Set<Transaction<E>> holders = new HashSet<>();
  /**
   * Lock mode, whether the lock is shared, or exclusive.
   */
  private LockMode mode;

  /**
   * Creates lockData for {@code firstHolder}.
   *
   * @param newLock - {@link Lock}
   * @param firstHolder - {@link Transaction}
   */
  LockData(Lock<E> newLock, Transaction<E> firstHolder) {
    holders.add(firstHolder);
    mode = newLock.mode;
  }
}
