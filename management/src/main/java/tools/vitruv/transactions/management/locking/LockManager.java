package tools.vitruv.transactions.management.locking;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;

import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;

/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 */
public class LockManager {
    /**
     * Computes the required {@link Lock}s that an {@link EChange} requires.
     *
     * @param change - {@link EChange}
     * @return {@link List}
     */
    public List<Lock<?>> computeLocksFor(EChange<?> change) {
        if (change instanceof CreateEObject<?> c) {
            return List.of(
                new ElementLock(c.getAffectedElement(), LockMode.EXCLUSIVE)
            );
        }
        if (change instanceof DeleteEObject<?> d) {
           return List.of(
               new ElementLock(d.getAffectedElement(), LockMode.EXCLUSIVE)
           );
        }
        if (change instanceof ReplaceSingleValuedEAttribute<?, ?> s) {
           return List.of(
               new ElementLock(s.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(s.getAffectedElement(), s.getAffectedFeature())
           );
        }
        if (change instanceof InsertEReference<?> a) {
           return List.of(
               new ElementLock(a.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock(a.getNewValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(a.getAffectedElement(), a.getAffectedFeature())
           );
        }
        if (change instanceof RemoveEReference<?> d) {
            return List.of(
               new ElementLock(d.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new ElementLock(d.getOldValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
               new FeatureLock(d.getAffectedElement(), d.getAffectedFeature())
            );
        }
        return List.of();
    }
}
