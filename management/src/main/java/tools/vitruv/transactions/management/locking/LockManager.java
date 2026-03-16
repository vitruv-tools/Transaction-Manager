package tools.vitruv.transactions.management.locking;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.vitruv.change.atomic.EChange;

import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.composite.description.VitruviusChange;

/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * @param <Element>
 */
public class LockManager<Element> {
    private final Set<Transaction<Element>> transactions = new HashSet<>();

    /**
     * Submits a new {@link VitruviusChange} to run as transaction.
     *
     * @param change - {@link VitruviusChange}
     */
    public Transaction<Element> submitTransaction(VitruviusChange<Element> change) {
        var newTransaction = new Transaction<>(change);
        transactions.add(newTransaction);
        return newTransaction;
    }

    /**
     * Acquires locks for the next operation of {@code transaction}, which must be running at this point.
     *
     * @param transaction - {@link Transaction}
     * @return {@link List}
     */
    public List<Lock<Element>> acquireNextLockFor(Transaction<Element> transaction) {
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
    public List<Lock<Element>> computeLocksFor(EChange<Element> change) {
        if (change instanceof CreateEObject<Element> c) {
            return List.of(
                new ElementLock(c.getAffectedElement(), LockMode.EXCLUSIVE)
            );
        }
        if (change instanceof DeleteEObject<Element> d) {
           return List.of(
               new ElementLock(d.getAffectedElement(), LockMode.EXCLUSIVE)
           );
        }
        if (change instanceof ReplaceSingleValuedEAttribute<Element, ?> s) {
           return List.of(
               new ElementLock(s.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(s.getAffectedElement(), s.getAffectedFeature())
           );
        }
        if (change instanceof InsertEReference<Element> a) {
           return List.of(
               new ElementLock(a.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock(a.getNewValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(a.getAffectedElement(), a.getAffectedFeature())
           );
        }
        if (change instanceof RemoveEReference<Element> d) {
            return List.of(
               new ElementLock(d.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock(d.getOldValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(d.getAffectedElement(), d.getAffectedFeature())
            );
        }
        return List.of();
    }
}
