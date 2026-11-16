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
 *   <li>{@code c:<nsUri>#<EClass>} the instances of a class (rows),</li>
 *   <li>{@code f:<nsUri>#<declaring EClass>.<feature>} a feature on all instances (columns); a
 *   type-level feature access also takes intention modes on {@code c:} of every class that has
 *   the feature, so that row and column locks meet,</li>
 *   <li>correspondence nodes ({@code corr}, {@code corr#<tag>}, {@code corr@<class>},
 *   {@code corr|<class>|<class>}, {@code corr|<class>|<class>|<tag>}),</li>
 *   <li>{@code res} the resource set (persisting models).</li>
 * </ul>
 * An instance lock on an element or a feature of it takes intention modes on {@code c:} of the
 * element's class, on {@code f:} of the feature and on the metamodels.
 * The reactions implementation is {@code LockRequests} in Vitruv-DSLs (branch
 * {@code feat/reactions-static-analysis}); its levels map one to one onto {@link LockMode}.
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
