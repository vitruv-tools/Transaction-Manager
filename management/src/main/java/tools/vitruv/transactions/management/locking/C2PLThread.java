package tools.vitruv.transactions.management.locking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;
import tools.vitruv.transactions.management.scheduling.SchedulingEventObserver;
import tools.vitruv.transactions.management.scheduling.TransactionExecutorThread;
import tools.vitruv.transactions.management.scheduling.VitruviusTransactionExecutorThread;

/**
 * A {@link C2PLThread} executes a {@link TransactionState} following the C2PL protocol.
 */
public class C2PLThread extends VitruviusTransactionExecutorThread {
  /**
   * The lock manager to consult for acquiring/releasing locks.
   */
  private final LockManager<EObject> lockManager;

  /**
   * Creates a new {@link C2PLThread} for {@code transactionState} on {@code multiModelEnvironment}
   * with {@code lockManager}.
   *
   * @param transactionState {@link TransactionState}
   * @param observers {@link ConcurrentLinkedDeque}
   * @param virtualModel {@link InternalVirtualModel}
   * @param lockManager {@link LockManager}
   */
  public C2PLThread(TransactionState<EObject> transactionState,
                    ConcurrentLinkedDeque<SchedulingEventObserver<EObject>> observers,
                    InternalVirtualModel virtualModel,
                    LockManager<EObject> lockManager) {
    super(transactionState, observers, virtualModel);
    this.lockManager = lockManager;
  }

  /**
   * Runs the scheduling algorithm, with the following steps.
   *
   * <ol>
   *     <li>Attempt to acquire all locks.</li>
   *     <li>If successful, execute the transaction on {@code multiModelEnvironment}.</li>
   *     <li>Otherwise, block the transaction, returning {@link TransactionStatus#BLOCKED}</li>
   *     <li>When an operation cannot be applied/an exception is thrown,
   *     undo the entire transaction and return {@link TransactionStatus#ABORTED}</li>.
   *     <li>When the transaction succeeds entirely, return {@link TransactionStatus#COMMITED}.</li>
   * </ol>
   *
   * @return result {@link TransactionExecutorThread.Result}
   *        result contains the {@link TransactionStatus} described above, and in the case of
   *        {@link TransactionStatus#COMMITED}, also the unblocked transactions.
   */
  @Override
  public TransactionExecutorThread.Result<EObject> call() {
    observers.forEach(observer -> observer.observeRunning(transactionState));
    transactionState.setToRunning();

    // Attempt to preclaim all locks
    while (transactionState.wantsToAcquireLocks()) {
      var blockingTransactions = lockManager.acquireLocksForNextOperation(transactionState);
      if (blockingTransactions.isPresent()) {
        handleBlock(blockingTransactions.get());
        // Inform observers about block
        observers.forEach(
            observer -> observer.observeBlockOf(
            this.transactionState,
                blockingTransactions.get())
        );
        return new Result<>(TransactionStatus.BLOCKED, Collections.emptyList());
      }
    }

    // Lock request succeeded, execute all operations
    super.startExecutionOfEChanges();
    try {
      while (transactionState.hasExecutableOperations()) {
        super.applyEChangeForward();
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      // An operation failed to execute, undo the transaction
      transactionState.setToAborting();
      // Throw away failing operation
      if (transactionState.hasOperationsToInvert()) {
        transactionState.getNextInverseOperation();
      }

      // Undo all previous operations
      while (transactionState.hasOperationsToInvert()) {
        super.applyEChangeBackward();
      }
    }

    // Release all locks
    releaseAllLocks();
    finishExecutionOfEChanges();

    var unblockedTransactions = new ArrayList<TransactionState<EObject>>();
    // Commit or abort
    if (transactionState.getStatus() == TransactionStatus.ABORTING) {
      observers.forEach(observers -> observers.observeAbort(transactionState));
      unblockedTransactions.addAll(lockManager.abort(transactionState));
    } else {
      observers.forEach(observers -> observers.observeCommit(transactionState));
      unblockedTransactions.addAll(lockManager.commit(transactionState));
    }
    return new Result<>(transactionState.getStatus(), unblockedTransactions);
  }

  /**
   * Handles a transaction block by releasing all locks that {@code transactionState} holds,
   * marking none its operations to be executable, and informing all observers.
   *
   * @param blockingTransactions - {@link Set}
   */
  private void handleBlock(Set<TransactionState<EObject>> blockingTransactions) {
    releaseAllLocks();
    // Go back to the start of the transaction, do not execute anything
    while (transactionState.goToPreviousOperationForExecutionCheck()) {
      continue;
    }
  }

  /**
   * Releases all held locks.
   */
  private void releaseAllLocks() {
    var locksToRelease = lockManager.getLocksHeldBy(transactionState);
    locksToRelease.forEach(lock -> lockManager.releaseLock(lock, transactionState));
  }
}
