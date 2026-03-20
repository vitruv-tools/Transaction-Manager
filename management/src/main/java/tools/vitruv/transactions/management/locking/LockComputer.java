package tools.vitruv.transactions.management.locking;

import lombok.experimental.UtilityClass;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;

import java.util.List;

/**
 * Utility class that computes lists of {@link Lock}s that an operation/{@link EChange}
 * must acquire to be admitted.
 */
@UtilityClass
public final class LockComputer {
    /**
     * Computes the required {@link Lock}s that an {@link EChange} requires.
     *
     * @param change - {@link EChange}
     * @return {@link List}
     */
    public static <E> List<Lock<E>> computeLocksFor(EChange<E> change) {
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
