package tools.vitruv.transactions.management.scheduling;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.resolve.AtomicEChangeResolverHelper;
import tools.vitruv.change.atomic.uuid.AtomicEChangeUuidResolver;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;

/**
 * A {@link VitruviusTransactionExecutorThread} is explicitly able to execute
 * {@link EChange}s forwards and backwards (for undo) against a {@link InternalVirtualModel}.
 */
public abstract class VitruviusTransactionExecutorThread
        extends TransactionExecutorThread<EObject> {
  /**
   * The multi-model environment where changes are applied to.
   */
  protected final InternalVirtualModel multiModelEnvironment;
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
   * Creates a new {@link VitruviusTransactionExecutorThread}.
   *
   * @param transactionState {@link TransactionState}
   * @param multiModelEnvironment {@link InternalVirtualModel}
   */
  public VitruviusTransactionExecutorThread(
          TransactionState<EObject> transactionState,
          InternalVirtualModel multiModelEnvironment) {
    super(transactionState);
    this.multiModelEnvironment = multiModelEnvironment;
    this.baseUuidResolver = multiModelEnvironment.getUuidResolver();
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
    var unresolvedChange = assignUuidToEChange(eChange);
    changeResolver.resolveAndApplyForward(unresolvedChange);
    return unresolvedChange;
  }

  /**
   * Applies the next inverse {@link EChange} on {@code multiModelEnvironment},
   * and returns the unresolved resulting change.
   *
   * @return {@link EChange}
   */
  protected EChange<Uuid> applyEChangeBackward() {
    var eChangeToInvert = transactionState.getNextInverseOperation();
    var unresolvedInverseChange = assignUuidToEChange(eChangeToInvert);
    changeResolver.resolveAndApplyForward(unresolvedInverseChange);
    return unresolvedInverseChange;
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
}
