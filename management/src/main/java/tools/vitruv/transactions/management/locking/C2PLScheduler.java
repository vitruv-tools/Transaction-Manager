package tools.vitruv.transactions.management.locking;

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
import tools.vitruv.transactions.management.AbstractScheduler;
import tools.vitruv.transactions.management.Transaction;


/**
 * A scheduler implementing the Conservative Two-Phase Locking (C2PL) algorithm.
 * This scheduler assumes that changes submitted to it are complete, i.e. the submitting
 * threads have executed the changes including CPRs for themselves.
 * Currently, this is "ensured" by checking the absence of consistency preservation rules in
 * the environment.
 */
public class C2PLScheduler extends AbstractScheduler<EObject> {
  /**
   * Lock manager used to determine if lock requests can be granted.
   */
  private final LockManager<EObject> lockManager = new LockManager<>();
  /**
   * Waiting queue for admitted transactions.
   */
  private final ConcurrentLinkedQueue<Transaction<EObject>> transactionQueue
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
  protected void applyTransactionOnEnvironment(Transaction<EObject> transaction) {
    var changeResolver = new AtomicEChangeUuidResolver(baseUuidResolver);
    while (transaction.hasExecutableOperations()) {
      var eChange = transaction.getNextOperationForExecution();
      var unresolvedChange = assignUuidToEChange(eChange);
      changeResolver.resolveAndApplyForward(unresolvedChange);
      observers.forEach(observer -> observer.observeExecutionOf(eChange, transaction));
    }
    temporaryMapping.clear();
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
  public void admitTransaction(VitruviusChange<EObject> change) {
    var newTransaction = lockManager.submitTransaction(change);
    transactionQueue.add(newTransaction);
    observers.forEach(observer -> observer.observeAdmission(newTransaction));
  }

  /**
   * Runs the scheduling algorithm, with the following steps:
   *
   * <ol>
   *     <li>Take the next queued transaction to execute.</li>
   *     <li>Attempt to acquire all locks.</li>
   *     <li>If successful, execute the transaction on {@code multiModelEnvironment}.</li>
   *     <li>Otherwise, block the transaction.</li>
   *     <li>When the lock request succeeds, </li>
   * </ol>
   *
   * @return boolean
   */
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
    Optional<Set<Transaction<EObject>>> blockingTransactions;
    while (transactionToExecute.wantsToAcquireLocks()) {
      blockingTransactions = lockManager.acquireLocksForNextOperation(transactionToExecute);
      if (blockingTransactions.isPresent()) {
        handleBlock(transactionToExecute, blockingTransactions.get());
        return true;
      }
    }

    // Lock request succeeded, execute all operations
    applyTransactionOnEnvironment(transactionToExecute);

    // Release all locks
    releaseAllLocksOf(transactionToExecute);
    // Commit, add all unblocked transactions to the waiting queue.
    var unblockedTransactions = lockManager.commit(transactionToExecute);
    transactionQueue.addAll(unblockedTransactions);
    observers.forEach(observer -> observer.observeCommit(transactionToExecute));
    return true;
  }

  /**
   * Handles a transaction block by releasing all locks that {@code transactionToExecute} holds,
   * marking none its operations to be executable, and informing all observers.
   *
   * @param transactionToExecute - {@link Transaction}
   * @param blockingTransactions - {@link Set}
   */
  private void handleBlock(Transaction<EObject> transactionToExecute,
                           Set<Transaction<EObject>> blockingTransactions) {
    releaseAllLocksOf(transactionToExecute);
    // Go back to the start of the transaction, do not execute anything
    while (transactionToExecute.goToPreviousOperationForExecutionCheck()) {
      continue;
    }
    observers.forEach(observer ->
        observer.observeBlockOf(transactionToExecute, blockingTransactions));
  }

  private void releaseAllLocksOf(Transaction<EObject> transaction) {
    var locksToRelease = lockManager.getLocksHeldBy(transaction);
    locksToRelease.forEach(lock -> lockManager.releaseLock(lock, transaction));
  }
}
