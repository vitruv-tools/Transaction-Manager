package tools.vitruv.transactions.management.locking;

import java.util.List;
import tools.vitruv.change.atomic.EChange;

/**
 * Computes the {@link Lock}s an operation ({@link EChange}) requires.
 *
 * <ul>
 *   <li>{@link ModelLevelLockProvider}: the locks on the model elements and features the change
 *   itself touches.</li>
 *   <li>{@link MetamodelLevelLockProvider}: additionally the locks on metamodel-level nodes
 *   (metamodels, classes, features on all instances) of what propagating the change may access,
 *   and the intention locks the model-level locks imply on them.</li>
 * </ul>
 *
 * @param <E> - The Element type.
 */
@FunctionalInterface
public interface LockProvider<E> {
  /**
   * The locks that {@code change} requires.
   *
   * @param change - {@link EChange}
   * @return {@link List}
   */
  List<Lock<E>> locksFor(EChange<E> change);
}
