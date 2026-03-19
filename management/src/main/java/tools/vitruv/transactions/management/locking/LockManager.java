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
     * Checks if a transaction has started to release locks.
     * In that case, it must not acquire further locks.
     */
    private final Map<Transaction<E>, Boolean> unlocking = new HashMap<>();
    /**
     * Registers waits-for relations between transactions.
     * We say that transaction t1 waits for t2 if ({@code waitsFor.get(t1).contains(t2)}).
     */
    private final Map<Transaction<E>, Set<Transaction<E>>> waitsForGraph = new HashMap<>();

    /**
     * Submits a new {@link VitruviusChange} to run as transaction.
     *
     * @param change - {@link VitruviusChange}
     */
    public synchronized Transaction<E> submitTransaction(VitruviusChange<E> change) {
        var newTransaction = new Transaction<>(change);
        locksForTransactions.put(newTransaction, new HashSet<>());
        unlocking.put(newTransaction, false);
        waitsForGraph.put(newTransaction, new HashSet<>());
        return newTransaction;
    }

    /**
     * Returns all locks that {@code transaction} holds.
     *
     * @param transaction - {@link Transaction}
     * @return {@link Set}
     */
    public synchronized Set<Lock<E>> getLocksHeldBy(Transaction<E> transaction) {
        checkArgument(locksForTransactions.containsKey(transaction), "The transaction is not currently active!");
        return Set.copyOf(locksForTransactions.get(transaction));
    }

    /**
     * Attempts to acquire all locks that the next {@link EChange} of {@code transaction} requires.
     * If the lock request fails, returns a mapping of:
     * <ol>
     *  <li>the lock that cannot be acquired,</li>
     *  <li>the other transactions blocking {@code transaction}.</li>
     * </ol>
     * 
     * @param transaction - {@link Transaction}
     * @return {@link Optional}
     */
    public synchronized Optional<Set<Transaction<E>>> acquireLocksForNextOperation(Transaction<E> transaction) {
        checkArgument(locksForTransactions.containsKey(transaction), "Transactions is not being processed!");
        checkArgument(unlocking.get(transaction) == false, "Transaction has started to unlock; it must not acquire further locks!");
        checkArgument(transaction.getStatus() == TransactionStatus.RUNNING, "Cannot acquire locks if the transaction is not running!");
        // Peek operation
        var operation = transaction.peekNextOperation();
        // Compute locks
        var locksToAcquire = computeLocksFor(operation);

        // Identify all blocking transactions.
        for (var lock: locksToAcquire) {
            var blockingTransactions = testLock(lock, transaction);
            if (blockingTransactions.isPresent()) {
                // Mark transaction as blocked
                waitsForGraph.get(transaction).addAll(blockingTransactions.get());
                transaction.setToBlocked();
                return blockingTransactions;
            }
        }
        // Else, transaction succeeds
        for (var lock: locksToAcquire) {
            setLock(lock, transaction);
        }
        transaction.markNextOperationAsExecutable();
        return Optional.empty();
    }

    /**
     * Acquires {@code lockToAcquire} for {@code transaction}.
     *
     * @param lockToAcquire - {@link Lock}
     * @param transaction -  {@link Transaction}
     * @return {@link Optional}
     *  The Optional type holds another transaction that already has the lock, and prevents its acquisition.
     */
    public synchronized Optional<Set<Transaction<E>>> testLock(Lock<E> lockToAcquire, Transaction<E> transaction) {
        var lockingTransactions = lockHolders.get(lockToAcquire);
        // If no other transaction holds the lock, the request succeeds.
        if (lockingTransactions == null) {
            return Optional.empty();
        }

        // If only the current transaction holds the lock, the request also succeeds.
        // Convert the lock, if required.
        if (lockingTransactions.size() == 1 && lockingTransactions.contains(transaction)) {
            return Optional.empty();
        }

        // If more than one transaction holds the lock in SIX mode, and the current transaction also
        // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
        // If more than one transaction holds it, this is an indicator thereof.
        if (lockToAcquire.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE &&
            lockMode.get(lockToAcquire) == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
            return Optional.empty();
        }

        // Return all locking transactions.
        return Optional.of(lockingTransactions);
    }


    /**
     * Actually acquires {@code lock} for {@code transaction} and updates information in this lock manager.
     *
     * @param lockToAcquire - {@link Lock}
     * @param transaction -  {@link Transaction}
     */
    public synchronized void setLock(Lock<E> lockToAcquire, Transaction<E> transaction) {
        checkArgument(locksForTransactions.containsKey(transaction), "This transaction may not acquire locks!");

        var lockingTransactions = lockHolders.get(lockToAcquire);
        // If no other transaction holds the lock, the request succeeds.
        if (lockingTransactions == null) {
            var newHolders = new HashSet<Transaction<E>>();
            newHolders.add(transaction);
            lockHolders.put(lockToAcquire, newHolders);
            locksForTransactions.get(transaction).add(lockToAcquire);
            lockMode.put(lockToAcquire, lockToAcquire.mode);
            return;
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
            return;
        }
        
        // If more than one transaction holds the lock in SIX mode, and the current transaction also
        // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
        // If more than one transaction holds it, this is an indicator thereof.
        if (lockToAcquire.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE &&
            lockMode.get(lockToAcquire) == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
            lockingTransactions.add(transaction);
            locksForTransactions.get(transaction).add(lockToAcquire);
        }
    }

    /**
     * Releases {@code lock} if it held by the {@code lockHolder} transaction.
     *
     * @param lock - {@link Lock}
     * @param lockHolder - {@link Transaction}
     */
    public synchronized void unsetLock(Lock<E> lock, Transaction<E> lockHolder) {
        // Transaction must be registered
        checkArgument(locksForTransactions.containsKey(lockHolder), "Transaction is not currently active!");
        var locks = locksForTransactions.get(lockHolder);
        checkArgument(locks.contains(lock), "Transaction does not hold the lock!");

        // Mark transactions as unlocking/shrinking
        unlocking.replace(lockHolder, true);
        // Release lock for transaction
        locks.remove(lock);
        // Remove lockHolder
        var lockHoldingTransactions = lockHolders.get(lock);
        lockHoldingTransactions.remove(lockHolder);
        // If no transactions hold the lock, remove the lock as well
        if (lockHoldingTransactions.isEmpty()) {
            lockHolders.remove(lock);
            lockMode.remove(lock);
        }
    }

    /**
     * Commits {@code transaction}, assuming that all its operations have been executed,
     * and all its locks have been released.
     * <p>
     * Upon that point, we update the {@link LockManager#waitsForGraph} relation, and return all
     * transactions that are now unblocked.
     *
     * @param transaction - {@link Transaction}
     * @return {@link Collection}
     */
    public synchronized Collection<Transaction<E>> commit(Transaction<E> transaction) {
        checkArgument(transaction.getStatus() == TransactionStatus.RUNNING, "Only running transactions can be committed!");
        checkArgument(!transaction.hasOperationsToExecute(), "Only transactions that have no more operations can be committed!");
        checkArgument(!locksForTransactions.get(transaction).isEmpty(), "Only transactions that do not have locks can be commited!");

        // Mark commit
        transaction.setToCommited();
        // Cleanup
        locksForTransactions.remove(transaction);
        unlocking.remove(transaction);
        waitsForGraph.remove(transaction);
        // Collect unblocked transactions
        return waitsForGraph.entrySet()
            .stream().peek(waitsFor -> waitsFor.getValue().remove(transaction))
            .filter(waitsFor -> waitsFor.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .toList();
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
