package tools.vitruv.transactions.management.locking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.resolve.AtomicEChangeResolverHelper;
import tools.vitruv.change.atomic.uuid.AtomicEChangeUuidResolver;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;
import tools.vitruv.transactions.management.scheduling.AbstractScheduler;

/**
 * A scheduler implementing the Conservative Two-Phase Locking (C2PL) algorithm.
 * This scheduler assumes that changes submitted to it are complete, i.e. the submitting
 * threads have executed the changes including CPRs for themselves.
 * Currently, this is "ensured" by checking the absence of consistency preservation rules in
 * the environment.
 */
public class C2PLScheduler extends AbstractScheduler<EObject, C2PLThread> {
  /**
   * Lock manager used to determine if lock requests can be granted.
   */
  private final LockManager<EObject> lockManager = new LockManager<>();
  /**
   * Waiting queue for admitted transactions.
   */
  private final ConcurrentLinkedQueue<TransactionState<EObject>> transactionQueue
      = new ConcurrentLinkedQueue<>();
  /**
   * Resolver required for applying atomic {@link EChange}s.
   */
  private final UuidResolver baseUuidResolver;
  /**
   * Temporary mapping for {@link EObject}s to {@link Uuid}s.
   * Used during the application of Transactions to {@link AbstractScheduler#multiModelEnvironment}.
   */
  private final Map<EObject, Uuid> temporaryMapping = new HashMap<>();

  /**
   * Creates a new {@link C2PLScheduler}.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment) {
    super(multiModelEnvironment);
    this.baseUuidResolver = multiModelEnvironment.getUuidResolver();
  }

  @Override
  protected void applyTransactionOnEnvironment(TransactionState<EObject> transaction) {
    var changeResolver = new AtomicEChangeUuidResolver(baseUuidResolver);
    while (transaction.hasExecutableOperations()) {
      var eChange = transaction.getNextOperationForExecution();
      var unresolvedChange = assignUuidToEChange(eChange);
      changeResolver.resolveAndApplyForward(unresolvedChange);
      observers.forEach(observer -> observer.observeExecutionOf(eChange, transaction));
    }

  }

  private void abortAndUndoAllExecutedOperations(TransactionState<EObject> transaction) {
    // Abort, go back to the last successful operation
    transaction.setToAborting();
    transaction.getNextInverseOperation();

    var changeResolver = new AtomicEChangeUuidResolver(baseUuidResolver);
    while (transaction.hasOperationsToInvert()) {
      var eChangeToInvert = transaction.getNextInverseOperation();
      var unresolvedInverseChange = assignUuidToEChange(eChangeToInvert);
      changeResolver.resolveAndApplyForward(unresolvedInverseChange);
      observers.forEach(observer -> observer.observeUndo(eChangeToInvert, transaction));
    }
  }

  /**
   * Assigns a {@link Uuid}s to {@code resolvedChange}.
   * If the base resolver used by the {@link AbstractScheduler#multiModelEnvironment} does not
   * have a mapping for {@code resolvedChange}, compute a mapping locally.
   *
   * @param resolvedChange - {@link EChange}
   * @return {@link EChange}
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
   * Admits a new transaction for {@code change} and reports this to
   * all observers.
   *
   * @param change - {@link VitruviusChange}
   */
  @Override
  public TransactionState<EObject> admitTransaction(VitruviusChange<EObject> change) {
    var newTransaction = lockManager.submitTransaction(change);
    transactionQueue.add(newTransaction);
    observers.forEach(observer -> observer.observeAdmission(newTransaction));
    return newTransaction;
  }


  @Override
  public boolean runNextStep() {
    if (transactionQueue.isEmpty()) {
      return false;
    }

    // Take next transaction, mark as running
    var transactionToExecute = transactionQueue.poll();
    transactionToExecute.setToRunning();
    observers.forEach(observer -> observer.observeRunning(transactionToExecute));

    // Attempt to preclaim all locks
    Optional<Set<TransactionState<EObject>>> blockingTransactions;
    while (transactionToExecute.wantsToAcquireLocks()) {
      blockingTransactions = lockManager.acquireLocksForNextOperation(transactionToExecute);
      if (blockingTransactions.isPresent()) {
        handleBlock(transactionToExecute, blockingTransactions.get());
        return true;
      }
    }

    // Lock request succeeded, execute all operations
    try {
      applyTransactionOnEnvironment(transactionToExecute);
    } catch (IllegalArgumentException | IllegalStateException e) {
      // An operation failed to execute, undo the transaction
      abortAndUndoAllExecutedOperations(transactionToExecute);
    } finally {
      // Release all locks
      releaseAllLocksOf(transactionToExecute);
      temporaryMapping.clear();
    }

    // Finish, add all unblocked transactions to the waiting queue.
    Collection<TransactionState<EObject>> unblockedTransactions = new ArrayList<>();
    if (transactionToExecute.getStatus() == TransactionStatus.RUNNING) {
      unblockedTransactions.addAll(lockManager.commit(transactionToExecute));
      observers.forEach(observer -> observer.observeCommit(transactionToExecute));
    } else {
      unblockedTransactions.addAll(lockManager.abort(transactionToExecute));
      observers.forEach(observer -> observer.observeAbort(transactionToExecute));
    }
    transactionQueue.addAll(unblockedTransactions);
    return true;
  }

  /**
   * Handles a transaction block by releasing all locks that {@code transactionToExecute} holds,
   * marking none its operations to be executable, and informing all observers.
   *
   * @param transactionToExecute - {@link TransactionState}
   * @param blockingTransactions - {@link Set}
   */
  private void handleBlock(TransactionState<EObject> transactionToExecute,
                           Set<TransactionState<EObject>> blockingTransactions) {
    releaseAllLocksOf(transactionToExecute);
    // Go back to the start of the transaction, do not execute anything
    while (transactionToExecute.goToPreviousOperationForExecutionCheck()) {
      continue;
    }
    observers.forEach(observer ->
        observer.observeBlockOf(transactionToExecute, blockingTransactions));
  }

  private void releaseAllLocksOf(TransactionState<EObject> transaction) {
    var locksToRelease = lockManager.getLocksHeldBy(transaction);
    locksToRelease.forEach(lock -> lockManager.releaseLock(lock, transaction));
  }
}
