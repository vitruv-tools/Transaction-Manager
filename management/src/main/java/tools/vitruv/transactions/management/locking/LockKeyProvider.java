package tools.vitruv.transactions.management.locking;

import java.util.List;
import tools.vitruv.change.atomic.EChange;

/**
 * Provides the coarse-grained (key) locks that propagating a change requires beyond the change
 * itself, e.g. the type-level locks derived from an access analysis of the reactions that react to
 * the change. Placeholder for a footprint SPI of change propagation specifications.
 *
 * <p>Contract: the keys are <em>canonical</em>, i.e. two accesses conflict iff they request the
 * same key in incompatible modes; hierarchies (e.g. a class below its metamodel) are expressed by
 * also requesting intention modes on the coarser keys. Key conventions, shared with
 * {@link HierarchicalLockComputer}:
 * <ul>
 *   <li>{@code mm:<nsUri>} a metamodel,</li>
 *   <li>{@code c:<nsUri>#<EClass>} the instances of a concrete class (child of its metamodel),</li>
 *   <li>{@code f:<nsUri>#<declaring EClass>.<feature>} a feature on all instances (child of its
 *   metamodel and of the classes of all instances that have the feature),</li>
 *   <li>{@code corr}, {@code corr:<tag>}, {@code corr:<tag>|<classKey>|<classKey>}
 *   correspondences,</li>
 *   <li>{@code res} the resource set (persisting models).</li>
 * </ul>
 *
 * @param <E> - The Element type.
 */
@FunctionalInterface
public interface LockKeyProvider<E> {
  /**
   * A request for a key lock.
   *
   * @param key - the canonical key
   * @param mode - the mode to lock it in
   */
  record KeyRequest(String key, LockMode mode) { }

  /**
   * The key locks that propagating {@code change} requires (beyond the locks of the change
   * itself).
   *
   * @param change - {@link EChange}
   * @return {@link List}
   */
  List<KeyRequest> keyLocksFor(EChange<E> change);
}
