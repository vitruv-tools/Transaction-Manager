package tools.vitruv.transactions.management.locking;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.atomic.feature.reference.ReplaceSingleValuedEReference;
import tools.vitruv.change.atomic.root.RootEChange;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.TransactionalChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.change.propagation.PropagationAbortedException;
import tools.vitruv.change.propagation.PropagationStepGuard;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Strict two-phase locking for change propagation with reactions or other specifications: a
 * {@link PropagationStepGuard} for the {@code ChangePropagator} that runs every propagation as a
 * transaction of a {@link LockManager}.
 *
 * <ul>
 *   <li>Before the user's change is applied: instance locks on the elements and features it
 *   changes (resolved through the repository's {@link UuidResolver}; elements created by the change
 *   itself are invisible to others and only take intentions on their class), with the implied
 *   type-level intentions.</li>
 *   <li>Before each propagation step: the key locks a {@link StepLockProvider} derives for the
 *   step's change and specification (for reactions: the canonical lock requests of their access
 *   analysis).</li>
 *   <li>Locks are kept until the propagation ends; then all are released.</li>
 *   <li>If the lock manager chooses the propagation as deadlock victim, the guard throws
 *   {@link PropagationAbortedException}: the propagator reverts and retries it, the transaction
 *   keeps its age.</li>
 * </ul>
 *
 * <p>Each thread runs one propagation at a time (the propagator is thread-confined per
 * propagation).
 */
public class LockingStepGuard implements PropagationStepGuard {
  /** The key locks one propagation step needs. */
  @FunctionalInterface
  public interface StepLockProvider {
    /**
     * The key locks that {@code specification} needs to propagate {@code change}.
     *
     * @param change - {@link EChange}
     * @param specification - {@link ChangePropagationSpecification}
     * @return {@link List}
     */
    List<LockKeyProvider.KeyRequest> keyLocksFor(EChange<EObject> change,
        ChangePropagationSpecification specification);
  }

  private final LockManager<EObject> lockManager;
  private final UuidResolver uuidResolver;
  private final StepLockProvider stepLocks;
  private final ThreadLocal<TransactionState<EObject>> transaction = new ThreadLocal<>();

  /**
   * Creates a new {@link LockingStepGuard}.
   *
   * @param lockManager - the lock manager shared by all propagating threads
   * @param uuidResolver - the UUID resolver of the model repository
   * @param stepLocks - the key locks of the propagation steps
   */
  public LockingStepGuard(LockManager<EObject> lockManager, UuidResolver uuidResolver,
      StepLockProvider stepLocks) {
    this.lockManager = lockManager;
    this.uuidResolver = uuidResolver;
    this.stepLocks = stepLocks;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void beginPropagation(VitruviusChange<Uuid> change) {
    // the transaction is identified by the user's change (equal on retry, so it keeps its age);
    // its operations are not used by the strict 2PL path, hence the unresolved change suffices
    var state = lockManager.submitTransaction(
        (VitruviusChange<EObject>) (VitruviusChange<?>) change);
    state.setToRunning();
    transaction.set(state);
  }

  @Override
  public void beforeApply(TransactionalChange<Uuid> change) {
    List<Lock<EObject>> instanceLocks = new ArrayList<>();
    List<LockKeyProvider.KeyRequest> keys = new ArrayList<>();
    for (EChange<Uuid> eChange : change.getEChanges()) {
      userChangeLocks(eChange, instanceLocks, keys);
    }
    acquire(HierarchicalLockComputer.withTypeIntentions(instanceLocks, keys));
  }

  @Override
  public void beforeStep(TransactionalChange<EObject> change,
      ChangePropagationSpecification specification) {
    List<LockKeyProvider.KeyRequest> keys = new ArrayList<>();
    for (EChange<EObject> eChange : change.getEChanges()) {
      keys.addAll(stepLocks.keyLocksFor(eChange, specification));
    }
    if (!keys.isEmpty()) {
      acquire(HierarchicalLockComputer.withTypeIntentions(List.of(), keys));
    }
  }

  @Override
  public void endPropagation(VitruviusChange<Uuid> change, boolean committed) {
    var state = transaction.get();
    transaction.remove();
    var cycle = deadlockCycle.get();
    deadlockCycle.remove();
    if (state == null) {
      return;
    }
    try {
      lockManager.endTransaction(state, committed);
      if (cycle != null) {
        // restart only after the others of the cycle finished, else the retry may grab its locks
        // before they wake up and recreate the same deadlock
        List<TransactionState<?>> others = new ArrayList<>(cycle);
        others.remove(state);
        lockManager.awaitEnd(others);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The wait-for cycle in which the current thread's transaction was chosen as victim. */
  private final ThreadLocal<List<? extends TransactionState<?>>> deadlockCycle =
      new ThreadLocal<>();

  private void acquire(List<Lock<EObject>> locks) {
    var state = transaction.get();
    if (state == null) {
      throw new IllegalStateException("No propagation is running on this thread");
    }
    try {
      lockManager.acquireLocks(state, locks);
    } catch (DeadlockVictimException e) {
      deadlockCycle.set(e.getCycle());
      throw new PropagationAbortedException("Chosen as deadlock victim", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PropagationAbortedException("Interrupted while waiting for locks", e);
    }
  }

  private void userChangeLocks(EChange<Uuid> eChange, List<Lock<EObject>> instanceLocks,
      List<LockKeyProvider.KeyRequest> keys) {
    if (eChange instanceof FeatureEChange<Uuid, ?> featureChange) {
      EStructuralFeature feature = featureChange.getAffectedFeature();
      EObject affected = resolve(featureChange.getAffectedElement());
      if (affected != null) {
        instanceLocks.add(
            new ElementLock<>(affected, LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
        instanceLocks.add(new FeatureLock<>(affected, feature));
      } else {
        // created by this change: only the type-level intention
        keys.add(new LockKeyProvider.KeyRequest(HierarchicalLockComputer.featureKey(feature),
            LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
      }
      for (Uuid value : referencedValues(eChange)) {
        EObject element = resolve(value);
        if (element != null) {
          instanceLocks.add(new ElementLock<>(element, LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
        }
      }
    } else if (eChange instanceof CreateEObject<Uuid> create) {
      keys.add(new LockKeyProvider.KeyRequest(
          HierarchicalLockComputer.classKey(create.getAffectedEObjectType()),
          LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
    } else if (eChange instanceof DeleteEObject<Uuid> delete) {
      EObject deleted = resolve(delete.getAffectedElement());
      if (deleted != null) {
        instanceLocks.add(new ElementLock<>(deleted, LockMode.EXCLUSIVE));
      }
    } else if (eChange instanceof RootEChange<?>) {
      // adding or removing a root changes the resource set
      keys.add(new LockKeyProvider.KeyRequest("res", LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
    }
  }

  private static List<Uuid> referencedValues(EChange<Uuid> eChange) {
    List<Uuid> values = new ArrayList<>();
    if (eChange instanceof InsertEReference<Uuid> insert) {
      values.add(insert.getNewValue());
    } else if (eChange instanceof RemoveEReference<Uuid> remove) {
      values.add(remove.getOldValue());
    } else if (eChange instanceof ReplaceSingleValuedEReference<Uuid> replace) {
      values.add(replace.getOldValue());
      values.add(replace.getNewValue());
    }
    values.removeIf(java.util.Objects::isNull);
    return values;
  }

  private EObject resolve(Uuid uuid) {
    return uuid != null && uuidResolver.hasEObject(uuid) ? uuidResolver.getEObject(uuid) : null;
  }
}
