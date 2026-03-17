package tools.vitruv.transactions.management.locking;

import java.util.*;
import tools.vitruv.change.atomic.EChange;

import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.composite.description.VitruviusChange;

import javax.lang.model.element.Element;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * @param <E> - The Element type.
 */
public class LockManager<E> {
    /**
     * Maps {@link Lock}s to one or more {@link Transaction}s that currently hold them.
     */
    private final Map<Lock<E>, Set<Transaction<E>>> lockHolders = new HashMap<>();
    /**
     * Maps {@link Transaction}s to one or more {@link Lock}s that they currently hold.
     */
    private final Map<Transaction<E>, Set<Lock<E>>> locksForTransactions = new HashMap<>();
    /**
     * Maps {@link Lock}s to the current lock mode.
     */
    private final Map<Lock<E>, LockMode> lockMode = new HashMap<>();

    /**
     * Submits a new {@link VitruviusChange} to run as transaction.
     *
     * @param change - {@link VitruviusChange}
     */
    public Transaction<E> submitTransaction(VitruviusChange<E> change) {
        var newTransaction = new Transaction<>(change);
        locksForTransactions.put(newTransaction, new HashSet<>());
        return newTransaction;
    }

    /**
     * Returns all locks that {@code transaction} holds.
     *
     * @param transaction - {@link Transaction}
     * @return {@link Set}
     */
    public Set<Lock<E>> getLocksHeldBy(Transaction<E> transaction) {
        checkArgument(locksForTransactions.containsKey(transaction), "The transaction is not currently active!");
        return Collections.unmodifiableSet(locksForTransactions.get(transaction));
    }

    /**
     * Attempts to acquire {@code lock} for {@code transaction}.
     *
     * @param lockToAcquire - {@link Lock}
     * @param transaction -  {@link Transaction}
     * @return {@link Optional}
     *  The Optional type holds another transaction that already has the lock, and prevents its acquisition.
     */
    public Optional<Set<Transaction<E>>> acquireLock(Lock<E> lockToAcquire, Transaction<E> transaction) {
        checkArgument(locksForTransactions.containsKey(transaction), "This transaction may not acquire locks!");

        var lockingTransactions = lockHolders.get(lockToAcquire);
        // If no other transaction holds the lock, the request succeeds.
        if (lockingTransactions == null) {
            var newHolders = new HashSet<Transaction<E>>();
            newHolders.add(transaction);
            lockHolders.put(lockToAcquire, newHolders);
            locksForTransactions.get(transaction).add(lockToAcquire);
            lockMode.put(lockToAcquire, lockToAcquire.mode);

            return Optional.empty();
        }

        // If only the current transaction holds the lock, the request also succeeds.
        // Convert the lock, if required.
        if (lockingTransactions.size() == 1 && lockingTransactions.contains(transaction)) {
            var currentLockMode = lockMode.get(lockToAcquire);
            var newLockMode = LockMode.highestLockMode(currentLockMode, lockToAcquire.mode);
            var upgradedLock = lockToAcquire.convert(newLockMode);

            lockMode.put(upgradedLock, newLockMode);
            lockHolders.put(upgradedLock, lockingTransactions);
            locksForTransactions.get(transaction).add(lockToAcquire);

            return Optional.empty();
        }
        
        // If more than one transaction holds the lock in SIX mode, and the current transaction also
        // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
        // If more than one transaction holds it, this is an indicator thereof.
        if (lockToAcquire.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE &&
            lockMode.get(lockToAcquire) == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
            lockingTransactions.add(transaction);
            locksForTransactions.get(transaction).add(lockToAcquire);
            return Optional.empty();
        }

        // Return all locking transactions.
        return Optional.of(lockingTransactions);
    }

    /**
     * Computes locks for the next operation of {@code transaction},
     * which must be running at this point.
     *
     * @param transaction - {@link Transaction}
     * @return {@link List}
     */
    public List<Lock<E>> computeNextLocksFor(Transaction<E> transaction) {
        var nextOperation = transaction.peekNextOperation();
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
