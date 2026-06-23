package tools.vitruv.transactions.management.scheduling;

import static com.google.common.base.Preconditions.checkState;
import static tools.vitruv.change.atomic.command.internal.ChangeCommandUtil.alreadyContainsObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.command.internal.ApplyEChangeSwitch;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.atomic.resolve.AtomicEChangeResolverHelper;
import tools.vitruv.change.atomic.uuid.AtomicEChangeUuidResolver;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.dsls.vitruvOCL.pipeline.VitruvOCL;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.InverseEChangeComputer;
import tools.vitruv.transactions.management.TransactionState;

/**
 * A {@link VitruviusTransactionExecutorThread} is explicitly able to execute
 * {@link EChange}s forwards and backwards (for undo) against a {@link InternalVirtualModel}.
 *
 * <p>The order of methods to call is:
 * {@code startExecutionOfEChanges}
 * -> {@code applyEChangeBackward/applyEChangeForward}
 * -> {@code finishExecutionOfEChanges}.
 *
 * <p><b>Warning</b>: Execution methods are not thread-safe per-se.
 * This is the responsibility of other classes extending {@link VitruviusTransactionExecutorThread}.
 */
public abstract class VitruviusTransactionExecutorThread
        extends TransactionExecutorThread<EObject> {
  /**
   * The multi-model environment where changes are applied to.
   */
  protected final InternalVirtualModel multiModelEnvironment;
  /**
   * Path to the VitruvOCL constraints file to check after applying a transaction's operations, or
   * empty if no consistency check should be performed.
   */
  protected final Optional<Path> constraintsFile;

  /**
   * Resolver required for applying atomic {@link EChange}s.
   */
  private AtomicEChangeUuidResolver changeResolver;
  /**
   * Provides mappings between {@link EObject}s and their assigned {@link Uuid}s in the
   * {@link VitruviusTransactionExecutorThread#multiModelEnvironment}, i.e. the current
   * state of the environment.
   */
  private final UuidResolver baseUuidResolver;
  /**
   * Temporary mapping for {@link EObject}s to {@link Uuid}s.
   * Used during the application of operations to
   * {@link VitruviusTransactionExecutorThread#multiModelEnvironment}.
   */
  private final Map<EObject, Uuid> temporaryMapping = new HashMap<>();
  /**
   * Stores information whether the applied change has had an effect,
   * and needs to be undone in case of a rollback.
   */
  private final Map<EChange<EObject>, Boolean> hasHadEffect = new HashMap<>();

  /**
   * Creates a new {@link VitruviusTransactionExecutorThread}.
   *
   * @param transactionState {@link TransactionState}
   * @param multiModelEnvironment {@link InternalVirtualModel}
   */
  public VitruviusTransactionExecutorThread(
      TransactionState<EObject> transactionState,
      ConcurrentLinkedDeque<SchedulingEventObserver<EObject>> observers,
      InternalVirtualModel multiModelEnvironment, Optional<Path> constraintsFile) {
    super(transactionState, observers);
    this.multiModelEnvironment = multiModelEnvironment;
    this.baseUuidResolver = multiModelEnvironment.getUuidResolver();
    this.constraintsFile = constraintsFile;
  }

  /**
   * Assigns a {@link Uuid}s to {@code resolvedChange}.
   * If the base resolver used by the {@link AbstractScheduler#multiModelEnvironment} does not
   * have a mapping for {@code resolvedChange}, compute a mapping locally.
   *
   * @param resolvedChange - {@link EChange}
   * @return {@link EChange}
   * @see VitruviusTransactionExecutorThread#applyEChangeForward()
   */
  private EChange<Uuid> assignUuidToEChange(EChange<EObject> resolvedChange) {
    return AtomicEChangeResolverHelper.resolveChange(
            resolvedChange,
            eObject -> {
              if (baseUuidResolver.hasUuid(eObject)) {
                return baseUuidResolver.getUuid(eObject);
              }
              if (temporaryMapping.containsKey(eObject)) {
                return temporaryMapping.get(eObject);
              }
              if (resolvedChange instanceof CreateEObject<EObject> createEObject
                      && createEObject.getAffectedElement() == eObject) {
                var newUuid = baseUuidResolver.generateUuid(eObject);
                temporaryMapping.put(eObject, newUuid);
                return newUuid;
              }
              throw new IllegalArgumentException(
                      String.format("Failed to assign a Uuid to %s", eObject));
            },
            (resource) -> baseUuidResolver.getResource(resource.getURI())
    );
  }

  /**
   * Applies the next {@link EChange} on {@code multiModelEnvironment} and returns
   * the unresolved resulting change.
   *
   * @return {@link EChange}
   */
  protected EChange<Uuid> applyEChangeForward() {
    var eChange = transactionState.getNextOperationForExecution();
    checkState(isApplicable(eChange), "EChange is not applicable, rollback required");
    hasHadEffect.put(eChange, hasEffect(eChange));
    var unresolvedChange = assignUuidToEChange(eChange);
    ApplyEChangeSwitch.applyEChange(eChange, true);
    updateEObjectToUUIDMapping(eChange, unresolvedChange);
    return unresolvedChange;
  }

  protected void updateEObjectToUUIDMapping(EChange<EObject> resolvedChange, EChange<Uuid> unresolvedChange) {
    var uuidResolver = this.baseUuidResolver;
    if (resolvedChange instanceof CreateEObject<EObject> createResolved
        && unresolvedChange instanceof CreateEObject<Uuid> createUnresolved) {
      uuidResolver.registerEObject(createUnresolved.getAffectedElement(), createResolved.getAffectedElement());
    }
    if (resolvedChange instanceof DeleteEObject<EObject> deleteResolved
        && unresolvedChange instanceof DeleteEObject<Uuid> deleteUnresolved) {
      uuidResolver.unregisterEObject(deleteUnresolved.getAffectedElement(), deleteResolved.getAffectedElement());
    }
  }

  /**
   * Applies the next inverse {@link EChange} on {@code multiModelEnvironment},
   * and returns the unresolved resulting change.
   *
   * @return {@link EChange}
   */
  protected EChange<Uuid> applyEChangeBackward() {
    var eChangeToInvert = transactionState.getNextInverseOperation();
    var eChangeInverse = InverseEChangeComputer.computeInverseOf(eChangeToInvert);
    checkState(isApplicable(eChangeInverse), "EChange is not applicable, rollback impossible!");

    var unresolvedInverseChange = assignUuidToEChange(eChangeInverse);
    if (hasHadEffect.get(eChangeToInvert)) {
      ApplyEChangeSwitch.applyEChange(eChangeToInvert, true);
      updateEObjectToUUIDMapping(eChangeInverse, unresolvedInverseChange);
    }
    return unresolvedInverseChange;
  }

  /**
   * Checks that {@code change} is applicable.
   *
   * <p>While Vitruvius handles most applicability conditions, we need this guarantee for
   * {@code ReplaceSingleValuedEAttribute} changes: the {@code oldValue} must equal
   * {@code change.getAffectedElement#eGet(change.getAffectedFeature)}.
   *
   * @param change - {@link EChange}
   * @return boolean
   */
  private static boolean isApplicable(EChange<EObject> change) {
    if (change instanceof ReplaceSingleValuedEAttribute<EObject, ?> replaceChange) {
      var attribute    = replaceChange.getAffectedFeature();
      var element      = replaceChange.getAffectedElement();
      if (element.eClass().getFeatureID(attribute) == -1) {
        return false;
      }
      var currentValue = element.eGet(attribute);
      var expectedValue = replaceChange.getOldValue();
      return (expectedValue == null && currentValue == null)
          || (expectedValue != null && expectedValue.equals(currentValue));
    }
    return true;
  }

  /**
   * Tests if applying {@code change} does actually have an effect on the multi-model environment.
   * This method must be called before {@link ApplyEChangeSwitch#applyEChange(EChange, boolean)}.
   *
   * <p>For insertion operations ({@link InsertEReference}), the element to insert
   * should not have been inserted already under the given reference.
   *
   * <p>For removal operations ({@link RemoveEReference}), the element to remove
   * should be contained in the reference where it is removed from.
   *
   * @param change - {@link EChange}
   * @return boolean
   */
  protected static boolean hasEffect(EChange<EObject> change) {
    if (change instanceof InsertEReference<EObject> insertion) {
      return !alreadyContainsObject(
          insertion.getAffectedElement(),
          insertion.getAffectedFeature(),
          insertion.getNewValue()
      );
    }
    if (change instanceof RemoveEReference<EObject> removal) {
      return alreadyContainsObject(
          removal.getAffectedElement(),
          removal.getAffectedFeature(),
          removal.getOldValue()
      );
    }
    return true;
  }

  /**
   * Prepares a new {@link VitruviusTransactionExecutorThread#changeResolver}.
   *
   * <p>Call before {@code applyEChangeForward}/{@code applyEChangeBackward}.
   */
  protected void startExecutionOfEChanges() {
    changeResolver = new AtomicEChangeUuidResolver(baseUuidResolver);
  }

  /**
   * Clears {@link VitruviusTransactionExecutorThread#temporaryMapping}.
   *
   * <p>Call at the end (abort/commit) of {@code transactionState}.
   */
  protected void finishExecutionOfEChanges() {
    temporaryMapping.clear();
  }

  /**
   * Checks the constraints in {@link #constraintsFile} (if any) against {@link
   * #multiModelEnvironment} after a transaction's operations have been applied.
   *
   * @throws IllegalStateException if any constraint is violated, so the caller rolls back the
   *     transaction the same way as for a failed operation
   */
  protected void checkConsistency() {
    if (constraintsFile.isEmpty()) {
      return;
    }
    VitruvOCL.registerVSUM(multiModelEnvironment);
    var result = VitruvOCL.evaluateConstraints(constraintsFile.get());
    if (!result.allSatisfied()) {
      throw new IllegalStateException(
          "Consistency check failed for transaction "
              + transactionState
              + ":\n"
              + result.getDetailedReport());
    }
  }
}
