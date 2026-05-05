package tools.vitruv.transactions.management.locking;

/**
 * Lock mode, how a {@link Lock} should be treated.
 */
public enum LockMode implements Comparable<LockMode> {
  /**
   * SHARED_INTENSIONAL_EXCLUSIVE:
   * The locked element can be locked by multiple transactions,
   * but for at least one of its children, there is an EXCLUSIVE lock.
   */
  SHARED_INTENSIONAL_EXCLUSIVE(),
  /**
   * Exclusive:
   * This lock can only be held by one transaction.
   */
  EXCLUSIVE();

  /**
   * Returns the highest lock mode of {@code l1} and [@code l2}.
   * An exclusive lock is higher than a shared intensional exclusive lock, i.e.
   *
   * @param l1 - {@link LockMode}
   * @param l2 - {@link LockMode}
   * @return {@link LockMode}
   */
  public static LockMode highestLockMode(LockMode l1, LockMode l2) {
    if (l1 == EXCLUSIVE) {
      return l1;
    }
    return l2;
  }
}
