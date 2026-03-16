package tools.vitruv.transactions.management.locking;

/**
 * Lock mode, how a {@link Lock should be treated}.
 */
public enum LockMode {
    /**
     * Exclusive locks can only be held by one transaction.
     */
    EXCLUSIVE,
    /**
     * SHARED_INTENSIONAL_EXCLUSIVE:
     * The locked element can be locked by multiple transactions,
     * but for at least one of its children, there is an EXCLUSIVE lock
     */
    SHARED_INTENSIONAL_EXCLUSIVE
}
