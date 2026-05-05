package tools.vitruv.transactions.management;

import lombok.experimental.UtilityClass;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.eobject.EobjectFactory;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;

/**
 * Utility class that computes inverse {@link EChange}s for undoing {@link Transaction}s
 * when they are aborted.
 */
@UtilityClass
public class InverseEChangeComputer {
  private static final TypeInferringAtomicEChangeFactory eChangeFactory =
      TypeInferringAtomicEChangeFactory.getInstance();

  /**
   * Given the {@link EChange} {@code input}, returns the {@link EChange} that would reverse it,
   * i.e. applying {@code computeInverseOf(input)} after {@code input} would leave a multimodel
   * environment in the state as it was before applying {@code input}.
   *
   * @param <E> Type of the elements.
   * @param input - {@link EChange}
   * @return {@link EChange}
   */
  public static <E> EChange<E> computeInverseOf(EChange<E> input) {
    return switch (input) {
      case CreateEObject<E> create:
        {
          var newDeleteEObjectChange = EobjectFactory.eINSTANCE.createDeleteEObject();
          newDeleteEObjectChange.setAffectedElement(create.getAffectedElement());
          yield (EChange<E>) newDeleteEObjectChange;
        }
      case DeleteEObject<E> delete:
        {
          var newCreateEObjectChange = EobjectFactory.eINSTANCE.createCreateEObject();
          newCreateEObjectChange.setAffectedElement(delete.getAffectedElement());
          yield (EChange<E>) newCreateEObjectChange;
        }
      case ReplaceSingleValuedEAttribute<E, ?> replace:
        yield eChangeFactory.createReplaceSingleAttributeChange(
            replace.getAffectedElement(),
            replace.getAffectedFeature(),
            replace.getNewValue(),
            replace.getOldValue()
        );
      case InsertEReference<E> insert:
        yield eChangeFactory.createRemoveReferenceChange(
            insert.getAffectedElement(),
            insert.getAffectedFeature(),
            insert.getNewValue(),
            insert.getIndex()
        );
      case RemoveEReference<E> remove:
        yield eChangeFactory.createInsertReferenceChange(
            remove.getAffectedElement(),
            remove.getAffectedFeature(),
            remove.getOldValue(),
            remove.getIndex()
        );
      default:
        throw new IllegalArgumentException(String.format("Change type %s is unknown!",
            input.eClass()));
    };
  }
}
