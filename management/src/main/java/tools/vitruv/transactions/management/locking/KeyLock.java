package tools.vitruv.transactions.management.locking;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * A {@link Lock} on a named, coarse-grained resource instead of a model element: a feature or a
 * class of a metamodel (type-level), a whole metamodel, correspondences of a tag, the resource
 * set. Keys are opaque strings to the lock manager; two key locks refer to the same resource iff
 * their keys are equal. Hierarchies between keys (e.g. a metamodel above its classes) are
 * expressed by the lock computer acquiring intention locks on the coarser keys
 * (see {@link HierarchicalLockComputer}).
 */
@EqualsAndHashCode(callSuper = true)
public class KeyLock<E> extends Lock<E> {
  /**
   * The key of the locked resource.
   */
  @Getter
  private final String key;

  /**
   * Creates a new {@link KeyLock}.
   *
   * @param key - {@link String}
   * @param mode - {@link LockMode}
   */
  public KeyLock(String key, LockMode mode) {
    super(null, mode);
    this.key = key;
  }

  @Override
  public Lock<E> convert(LockMode newMode) {
    return new KeyLock<>(key, newMode);
  }

  @Override
  public String toString() {
    return "KeyLock(" + key + ", " + mode + ")";
  }
}
