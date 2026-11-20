package tools.vitruv.transactions.management.locking;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.ENamedElement;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;

/**
 * A {@link Lock} on a metamodel-level node instead of a model element: it covers all model
 * elements of a kind, also those another transaction has not touched yet. The nodes form a
 * hierarchy above the model-level locks (multi-granularity locking):
 *
 * <ul>
 *   <li>{@link #onMetamodel(EPackage)}: all models of a metamodel,</li>
 *   <li>{@link #onClass(EClass)}: all instances of a class (any of their features),</li>
 *   <li>{@link #onFeature(EStructuralFeature)}: one feature on all instances of its class.</li>
 * </ul>
 *
 * <p>A lock below takes the intention mode on the nodes above it (see
 * {@link MetamodelLevelLockProvider}): e.g. writing a feature of one element takes
 * {@link LockMode#SHARED_INTENSIONAL_EXCLUSIVE} on the feature node, the element's class and the
 * metamodel, so that a transaction that reads that feature on all instances
 * ({@link LockMode#SHARED} on the feature node) conflicts with it, while one that reads another
 * feature does not.
 *
 * @param <E> - The Element type of the model-level locks (metamodel-level locks have no element).
 */
@EqualsAndHashCode(callSuper = true)
public final class MetamodelLevelLock<E> extends Lock<E> {
  /**
   * The kind of metamodel-level node.
   */
  public enum Kind {
    /** All models of a metamodel (its root {@link EPackage}). */
    METAMODEL,
    /** All instances of an {@link EClass}. */
    CLASS,
    /** An {@link EStructuralFeature} on all instances of its class. */
    FEATURE
  }

  /**
   * The kind of node.
   */
  @Getter
  private final Kind kind;
  /**
   * The locked metamodel element: an {@link EPackage}, {@link EClass} or
   * {@link EStructuralFeature}, according to {@link #getKind()}.
   */
  @Getter
  private final ENamedElement node;

  private MetamodelLevelLock(Kind kind, ENamedElement node, LockMode mode) {
    super(null, mode);
    this.kind = kind;
    this.node = node;
  }

  /**
   * A lock on all models of the metamodel of {@code ePackage} (its root package).
   *
   * @param ePackage - {@link EPackage}
   * @param mode - {@link LockMode}
   * @param <E> - The Element type.
   * @return {@link MetamodelLevelLock}
   */
  public static <E> MetamodelLevelLock<E> onMetamodel(EPackage ePackage, LockMode mode) {
    EPackage root = ePackage;
    while (root.getESuperPackage() != null) {
      root = root.getESuperPackage();
    }
    return new MetamodelLevelLock<>(Kind.METAMODEL, root, mode);
  }

  /**
   * A lock on all instances of {@code eClass}.
   *
   * @param eClass - {@link EClass}
   * @param mode - {@link LockMode}
   * @param <E> - The Element type.
   * @return {@link MetamodelLevelLock}
   */
  public static <E> MetamodelLevelLock<E> onClass(EClass eClass, LockMode mode) {
    return new MetamodelLevelLock<>(Kind.CLASS, eClass, mode);
  }

  /**
   * A lock on {@code feature} on all instances of its class (and subclasses).
   *
   * @param feature - {@link EStructuralFeature}
   * @param mode - {@link LockMode}
   * @param <E> - The Element type.
   * @return {@link MetamodelLevelLock}
   */
  public static <E> MetamodelLevelLock<E> onFeature(EStructuralFeature feature, LockMode mode) {
    return new MetamodelLevelLock<>(Kind.FEATURE, feature, mode);
  }

  @Override
  public Lock<E> convert(LockMode newMode) {
    return new MetamodelLevelLock<>(kind, node, newMode);
  }

  @Override
  public String toString() {
    return "MetamodelLevelLock(" + kind + " " + node.getName() + ", " + mode + ")";
  }
}
