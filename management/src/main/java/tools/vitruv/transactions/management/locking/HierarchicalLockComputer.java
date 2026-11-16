package tools.vitruv.transactions.management.locking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.change.atomic.EChange;

/**
 * Computes the locks for one propagation step: the instance locks of the change itself (as
 * {@link LockComputer} does), the intention locks they imply on the type-level keys, and the key
 * locks a {@link LockKeyProvider} derives from what propagating the change may access.
 *
 * <p>Multi-granularity: an instance lock on a feature of an element takes the intention mode on
 * {@code f:<feature>}, on {@code c:<class of the element>} and on the metamodels of both; an
 * instance lock on an element takes the intention mode on its class and metamodel. A type-level
 * write ({@link LockMode#EXCLUSIVE} on {@code f:} or {@code c:}) therefore conflicts with every
 * instance-level access below it, and type-level reads ({@link LockMode#SHARED}) conflict with
 * instance-level writes only.
 *
 * <p>Requests for the same lock are merged into the least mode covering all of them.
 */
public final class HierarchicalLockComputer {
  private final LockKeyProvider<EObject> keyProvider;

  /**
   * Creates a new {@link HierarchicalLockComputer}.
   *
   * @param keyProvider - {@link LockKeyProvider}
   */
  public HierarchicalLockComputer(LockKeyProvider<EObject> keyProvider) {
    this.keyProvider = keyProvider;
  }

  /**
   * Computes the locks that propagating {@code change} requires.
   *
   * @param change - {@link EChange}
   * @return {@link List}
   */
  public List<Lock<EObject>> computeLocksFor(EChange<EObject> change) {
    Map<Lock<EObject>, LockMode> merged = new LinkedHashMap<>();
    for (Lock<EObject> instanceLock : LockComputer.computeLocksFor(change)) {
      add(merged, instanceLock);
      LockMode intention = instanceLock.getMode().intention();
      EObject element = instanceLock.getRoot();
      if (element != null) {
        addKey(merged, classKey(element.eClass()), intention);
        addKey(merged, metamodelKey(element.eClass()), intention);
      }
      if (instanceLock instanceof FeatureLock<EObject> featureLock) {
        EStructuralFeature feature = featureLock.getFeature();
        addKey(merged, featureKey(feature), intention);
        addKey(merged, metamodelKey(feature.getEContainingClass()), intention);
      }
    }
    for (LockKeyProvider.KeyRequest request : keyProvider.keyLocksFor(change)) {
      addKey(merged, request.key(), request.mode());
    }
    List<Lock<EObject>> result = new ArrayList<>(merged.size());
    merged.forEach((lock, mode) -> result.add(lock.convert(mode)));
    return result;
  }

  private static void addKey(Map<Lock<EObject>, LockMode> merged, String key, LockMode mode) {
    add(merged, new KeyLock<>(key, mode));
  }

  private static void add(Map<Lock<EObject>, LockMode> merged, Lock<EObject> lock) {
    merged.merge(lock, lock.getMode(), LockMode::highestLockMode);
  }

  /**
   * Key of a feature on all instances.
   *
   * @param feature - {@link EStructuralFeature}
   * @return {@link String}
   */
  public static String featureKey(EStructuralFeature feature) {
    return "f:" + classId(feature.getEContainingClass()) + "." + feature.getName();
  }

  /**
   * Key of the instances of a class.
   *
   * @param eClass - {@link EClass}
   * @return {@link String}
   */
  public static String classKey(EClass eClass) {
    return "c:" + classId(eClass);
  }

  /**
   * Key of the metamodel of a class.
   *
   * @param eClass - {@link EClass}
   * @return {@link String}
   */
  public static String metamodelKey(EClass eClass) {
    return "mm:" + eClass.getEPackage().getNsURI();
  }

  private static String classId(EClass eClass) {
    return eClass.getEPackage().getNsURI() + "#" + eClass.getName();
  }
}
