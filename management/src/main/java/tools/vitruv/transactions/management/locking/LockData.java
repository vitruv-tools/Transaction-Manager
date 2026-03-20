package tools.vitruv.transactions.management.locking;

import lombok.Data;
import tools.vitruv.transactions.management.Transaction;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents information about a {@link Lock} within a lock manager.
 *
 * @param <E> - The data type of locking {@link Transaction}s.
 */
@Data
public class LockData<E> {
    /**
     * What transactions hold this lock at present?
     */
    private final Set<Transaction<E>> holders = new HashSet<>();
    /**
     * Is the lock shared, or exclusive?
     */
    private LockMode mode;

    /**
     * Creates lockData for {@code firstHolder}.
     *
     * @param newLock - {@link Lock}
     * @param firstHolder - {@link Transaction}
     */
    public LockData(Lock<E> newLock, Transaction<E> firstHolder) {
        holders.add(firstHolder);
        mode = newLock.mode;
    }
}
