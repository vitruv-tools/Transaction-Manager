package tools.vitruv.transactions.management.locking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.change.atomic.EChange;

/**
 * Computes the locks for one propagation step on the metamodel level, in addition to the model
 * level: the model-level locks of the change itself ({@link ModelLevelLockProvider}), the intention
 * locks they imply on the metamodel-level nodes above them, and the {@link MetamodelLevelLock}s
 * that an access provider derives from what propagating the change may access (e.g. an access
 * analysis of the reactions that react to it), with the intentions those imply on their metamodel.
 *
 * <p>Multi-granularity: a lock on a feature of an element takes the intention mode on the feature
 * node, on the class of the element and on the metamodels; a lock on an element takes the
 * intention mode on its class and metamodel; a lock on a feature or class node takes the
 * intention mode on its metamodel. A metamodel-level write ({@link LockMode#EXCLUSIVE} on a feature
 * or class node) therefore conflicts with every model-level access below it, and a
 * metamodel-level read ({@link LockMode#SHARED}) conflicts with model-level writes only.
 *
 * <p>Requests for the same lock are merged into the least mode covering all of them.
 */
public final class MetamodelLevelLockProvider implements LockProvider<EObject> {
  private final LockProvider<EObject> modelLevel = new ModelLevelLockProvider<>();
  private final LockProvider<EObject> metamodelLevelAccess;

  /**
   * Creates a new {@link MetamodelLevelLockProvider}.
   *
   * @param metamodelLevelAccess - the metamodel-level locks of what propagating a change may
   *                             access (beyond the change itself)
   */
  public MetamodelLevelLockProvider(LockProvider<EObject> metamodelLevelAccess) {
    this.metamodelLevelAccess = metamodelLevelAccess;
  }

  @Override
  public List<Lock<EObject>> locksFor(EChange<EObject> change) {
    return withIntentions(modelLevel.locksFor(change), metamodelLevelAccess.locksFor(change));
  }

  /**
   * The given locks plus the intention locks they imply on the metamodel-level nodes above them,
   * merged per lock into the least mode covering all requests.
   *
   * @param modelLevelLocks - locks on elements and their features
   * @param metamodelLevelLocks - locks on metamodel-level nodes
   * @return {@link List}
   */
  public static List<Lock<EObject>> withIntentions(
      List<? extends Lock<EObject>> modelLevelLocks,
      List<? extends Lock<EObject>> metamodelLevelLocks) {
    Map<Lock<EObject>, LockMode> merged = new LinkedHashMap<>();
    for (Lock<EObject> lock : modelLevelLocks) {
      add(merged, lock);
      LockMode intention = lock.getMode().intention();
      EObject element = lock.getRoot();
      if (element != null) {
        add(merged, MetamodelLevelLock.onClass(element.eClass(), intention));
        add(merged, MetamodelLevelLock.onMetamodel(element.eClass().getEPackage(), intention));
      }
      if (lock instanceof FeatureLock<EObject> featureLock) {
        EStructuralFeature feature = featureLock.getFeature();
        add(merged, MetamodelLevelLock.onFeature(feature, intention));
        add(merged, MetamodelLevelLock.onMetamodel(
            feature.getEContainingClass().getEPackage(), intention));
      }
    }
    for (Lock<EObject> lock : metamodelLevelLocks) {
      add(merged, lock);
      if (lock instanceof MetamodelLevelLock<EObject> node) {
        var metamodel = switch (node.getKind()) {
          case CLASS -> ((org.eclipse.emf.ecore.EClass) node.getNode()).getEPackage();
          case FEATURE -> ((EStructuralFeature) node.getNode()).getEContainingClass().getEPackage();
          case METAMODEL -> null;
        };
        if (metamodel != null) {
          add(merged, MetamodelLevelLock.onMetamodel(metamodel, lock.getMode().intention()));
        }
      }
    }
    List<Lock<EObject>> result = new ArrayList<>(merged.size());
    merged.forEach((lock, mode) -> result.add(lock.convert(mode)));
    return result;
  }

  private static void add(Map<Lock<EObject>, LockMode> merged, Lock<EObject> lock) {
    merged.merge(lock, lock.getMode(), LockMode::highestLockMode);
  }
}
