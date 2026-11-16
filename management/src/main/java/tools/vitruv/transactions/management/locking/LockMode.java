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
 *                                IS   IX   S    SIX  X
 *   INTENTION_SHARED (IS)        yes  yes  yes  yes  no
 *   SHARED_INTENSIONAL_EXCL.(IX) yes  yes  no   no   no
 *   SHARED (S)                   yes  no   yes  no   no
 *   SHARED_WITH_INTENTION_EXCL.  yes  no   no   no   no
 *   EXCLUSIVE (X)                no   no   no   no   no
 * </pre>
 *
 * <p>{@link #SHARED_INTENSIONAL_EXCLUSIVE} keeps its original semantics: it may be held by several
 * transactions at once and announces exclusive locks below, i.e. it behaves like the classic
 * intention-exclusive (IX) mode. The classic SIX mode (shared, plus exclusive locks below) is
 * {@link #SHARED_WITH_INTENTION_EXCLUSIVE}.
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
   * Shared, and exclusive locks on finer-grained nodes below (classic SIX): e.g. reading all
   * instances of a class while writing a feature of some of them.
   */
  SHARED_WITH_INTENTION_EXCLUSIVE(),
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
    if (this == SHARED_WITH_INTENTION_EXCLUSIVE || other == SHARED_WITH_INTENTION_EXCLUSIVE) {
      return false;
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
    if (l1 == EXCLUSIVE || l2 == EXCLUSIVE) {
      return EXCLUSIVE;
    }
    // two different ones of SHARED_INTENSIONAL_EXCLUSIVE, SHARED, SHARED_WITH_INTENTION_EXCLUSIVE
    return SHARED_WITH_INTENTION_EXCLUSIVE;
  }
}
