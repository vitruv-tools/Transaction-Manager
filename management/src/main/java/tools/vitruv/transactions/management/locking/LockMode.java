package tools.vitruv.transactions.management.locking;

/**
 * Lock mode, how a {@link Lock} should be treated.
 *
 * <p>The modes follow multi-granularity locking: intention modes announce shared or exclusive
 * locks on finer-grained nodes below the locked one (e.g. a feature of the locked element, or an
 * element of a locked type), {@link #SHARED} allows concurrent readers, {@link #EXCLUSIVE} a
 * single writer. Compatibility (see {@link #isCompatibleWith(LockMode)}):
 *
 * <pre>
 *                      IS   SIX(=IX)  S    X
 *   INTENTION_SHARED   yes  yes       yes  no
 *   SHARED_INT._EXCL.  yes  yes       no   no
 *   SHARED             yes  no        yes  no
 *   EXCLUSIVE          no   no        no   no
 * </pre>
 *
 * <p>{@link #SHARED_INTENSIONAL_EXCLUSIVE} keeps its original semantics: it may be held by several
 * transactions at once and announces exclusive locks below, i.e. it behaves like the classic
 * intention-exclusive (IX) mode. There is no combined S+IX mode; combining both yields
 * {@link #EXCLUSIVE} (stronger, hence still correct).
 */
public enum LockMode {
  /**
   * Announces shared locks on finer-grained nodes below the locked one.
   */
  INTENTION_SHARED(),
  /**
   * SHARED_INTENSIONAL_EXCLUSIVE:
   * The locked element can be locked by multiple transactions,
   * but for at least one of its children, there is an EXCLUSIVE lock.
   */
  SHARED_INTENSIONAL_EXCLUSIVE(),
  /**
   * Shared: may be held by several readers at once.
   */
  SHARED(),
  /**
   * Exclusive:
   * This lock can only be held by one transaction.
   */
  EXCLUSIVE();

  /**
   * Whether a lock in this mode may be held while another transaction holds the same lock in
   * mode {@code other}.
   *
   * @param other - {@link LockMode}
   * @return boolean
   */
  public boolean isCompatibleWith(LockMode other) {
    if (this == EXCLUSIVE || other == EXCLUSIVE) {
      return false;
    }
    if (this == INTENTION_SHARED || other == INTENTION_SHARED) {
      return true;
    }
    // remaining: SHARED_INTENSIONAL_EXCLUSIVE and SHARED
    return this == other;
  }

  /**
   * The intention mode that a lock in this mode requires on the coarser-grained nodes above it.
   *
   * @return {@link LockMode}
   */
  public LockMode intention() {
    return this == SHARED || this == INTENTION_SHARED
        ? INTENTION_SHARED : SHARED_INTENSIONAL_EXCLUSIVE;
  }

  /**
   * Returns the least lock mode that is at least as strong as {@code l1} and {@code l2}.
   *
   * @param l1 - {@link LockMode}
   * @param l2 - {@link LockMode}
   * @return {@link LockMode}
   */
  public static LockMode highestLockMode(LockMode l1, LockMode l2) {
    if (l1 == l2 || l2 == INTENTION_SHARED) {
      return l1;
    }
    if (l1 == INTENTION_SHARED) {
      return l2;
    }
    // differing modes out of SHARED_INTENSIONAL_EXCLUSIVE, SHARED, EXCLUSIVE
    return EXCLUSIVE;
  }
}
