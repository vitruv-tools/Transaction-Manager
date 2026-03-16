package tools.vitruv.transactions.management.locking;

import java.util.*;
import tools.vitruv.change.atomic.EChange;

import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.composite.description.VitruviusChange;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * @param <E> - The Element type.
 */
public class LockManager<E> {
    /**
     * Transactions that are currently being processed.
     */
    private final Set<Transaction<E>> transactions = new HashSet<>();
    /**
     * Maps {@link Lock}s to one or more {@link Transaction}s that currently hold them.
     */
    private final Map<Lock<E>, Set<Transaction<E>>> holders = new HashMap<>();

    /**
     * Submits a new {@link VitruviusChange} to run as transaction.
     *
     * @param change - {@link VitruviusChange}
     */
    public Transaction<E> submitTransaction(VitruviusChange<E> change) {
        var newTransaction = new Transaction<>(change);
        transactions.add(newTransaction);
        return newTransaction;
    }

    /**
     * Attempts to acquire {@code lock} for {@code transaction}.
     *
     * @param lock - {@link Lock}
     * @param transaction -  {@link Transaction}
     * @return {@link Optional}
     *  The Optional type holds another transaction that already has the lock, and prevents its acquisition.
     */
    public Optional<Transaction<E>> acquireLock(Lock<E> lock, Transaction<E> transaction) {
        checkArgument(transactions.contains(transaction), "This transaction may not acquire locks!");

        var lockingTransactions = holders.get(lock);
        // If no other transaction holds the lock, the request succeeds.
        if (lockingTransactions == null) {
            var newHolders = new HashSet<Transaction<E>>();
            newHolders.add(transaction);
            holders.put(lock, newHolders);
            return Optional.empty();
        }

        // If only the current transaction holds the lock, the request also succeeds.
        // Convert the lock in that case.
        if (lockingTransactions.size() == 1 && lockingTransactions.contains(transaction)) {
            holders.put(lock.convert(LockMode.EXCLUSIVE), lockingTransactions);
            return Optional.empty();
        }
        
        // If more than one transaction holds the lock in SIX mode, and the current transaction also
        // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
        if (lock.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
            lockingTransactions.add(transaction);
            return Optional.empty();
        }

        // Return some locking transaction.
        return lockingTransactions.stream().findAny();
    }

    /**
     * Acquires locks for the next operation of {@code transaction}, which must be running at this point.
     *
     * @param transaction - {@link Transaction}
     * @return {@link List}
     */
    public List<Lock<E>> computeNextLocksFor(Transaction<E> transaction) {
        var nextOperation = transaction.peekNextOperation();
        transaction.acceptNextOperation();
        return computeLocksFor(nextOperation);
    }

    /**
     * Computes the required {@link Lock}s that an {@link EChange} requires.
     *
     * @param change - {@link EChange}
     * @return {@link List}
     */
    public List<Lock<E>> computeLocksFor(EChange<E> change) {
        if (change instanceof CreateEObject<E> c) {
            return List.of(
                new ElementLock<>(c.getAffectedElement(), LockMode.EXCLUSIVE)
            );
        }
        if (change instanceof DeleteEObject<E> d) {
           return List.of(
               new ElementLock<>(d.getAffectedElement(), LockMode.EXCLUSIVE)
           );
        }
        if (change instanceof ReplaceSingleValuedEAttribute<E, ?> s) {
           return List.of(
               new ElementLock<>(s.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock<>(s.getAffectedElement(), s.getAffectedFeature())
           );
        }
        if (change instanceof InsertEReference<E> a) {
           return List.of(
               new ElementLock<>(a.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock<>(a.getNewValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock<>(a.getAffectedElement(), a.getAffectedFeature())
           );
        }
        if (change instanceof RemoveEReference<E> d) {
            return List.of(
               new ElementLock<>(d.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock<>(d.getOldValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock<>(d.getAffectedElement(), d.getAffectedFeature())
            );
        }
        return List.of();
    }
}
