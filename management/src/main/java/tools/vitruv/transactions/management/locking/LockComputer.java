package tools.vitruv.transactions.management.locking;

import java.util.List;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.atomic.root.InsertRootEObject;
import tools.vitruv.change.atomic.root.RemoveRootEObject;

/**
 * Utility class that computes lists of {@link Lock}s that an operation/{@link EChange}
 * must acquire to be admitted.
 */

public final class LockComputer {
  private LockComputer() {}

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
    if (change instanceof RemoveEReference<E> r) {
      return List.of(
          new ElementLock<>(r.getAffectedElement(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
          new ElementLock<>(r.getOldValue(), LockMode.SHARED_INTENSIONAL_EXCLUSIVE),
          new FeatureLock<>(r.getAffectedElement(), r.getAffectedFeature())
      );
    }
    if (change instanceof InsertRootEObject<E> iR) {
      return List.of(
          new ElementLock<>(iR.getNewValue(), LockMode.EXCLUSIVE)
      );
    }
    if (change instanceof RemoveRootEObject<E> rR) {
      return List.of(
          new ElementLock<>(rR.getOldValue(), LockMode.EXCLUSIVE)
      );
    }
    return List.of();
  }
}
