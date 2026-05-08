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
 * Utility class that computes inverse {@link EChange}s for undoing {@link TransactionState}s
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
    if (input instanceof CreateEObject<E> create) {
      var newDeleteEObjectChange = EobjectFactory.eINSTANCE.createDeleteEObject();
      newDeleteEObjectChange.setAffectedElement(create.getAffectedElement());
      return (EChange<E>) newDeleteEObjectChange;
    }
    if (input instanceof DeleteEObject<E> delete) {
      var newCreateEObjectChange = EobjectFactory.eINSTANCE.createCreateEObject();
      newCreateEObjectChange.setAffectedElement(delete.getAffectedElement());
      return (EChange<E>) newCreateEObjectChange;
    }
    if (input instanceof ReplaceSingleValuedEAttribute<E, ?> replace) {
      return eChangeFactory.createReplaceSingleAttributeChange(
          replace.getAffectedElement(),
          replace.getAffectedFeature(),
          replace.getNewValue(),
          replace.getOldValue()
      );
    }
    if (input instanceof InsertEReference<E> insert) {
      return eChangeFactory.createRemoveReferenceChange(
          insert.getAffectedElement(),
          insert.getAffectedFeature(),
          insert.getNewValue(),
          insert.getIndex()
      );
    }
    if (input instanceof RemoveEReference<E> remove) {
      return eChangeFactory.createInsertReferenceChange(
          remove.getAffectedElement(),
          remove.getAffectedFeature(),
          remove.getOldValue(),
          remove.getIndex()
      );
    }
    throw new IllegalArgumentException(String.format("Change type %s is unknown!",
        input.eClass()));
  }
}

