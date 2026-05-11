package tools.vitruv.transactions.management.locking;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.TransactionStatus;
import tools.vitruv.transactions.management.scheduling.AbstractScheduler;
import tools.vitruv.transactions.management.scheduling.TransactionExecutorThread;

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
   * Executor service for transaction threads.
   */
  private final ExecutorService transactionThreadService = Executors.newSingleThreadExecutor();

  /**
   * Creates a new {@link C2PLScheduler}.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment) {
    super(multiModelEnvironment);
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
    observers.forEach(observer -> observer.observeRunning(transactionToExecute));

    // Submit to transactionThreadService
    var transactionThread = new C2PLThread(transactionToExecute,
            multiModelEnvironment,
            lockManager);
    // Await execution
    var future = transactionThreadService.submit(transactionThread);
    TransactionExecutorThread.Result<EObject> executionResult;
    try {
      executionResult = future.get();
    } catch (Exception e) {
      return false;
    }

    // Report result to observers
    if (executionResult.status() == TransactionStatus.COMMITED) {
      observers.forEach(observer -> observer.observeCommit(transactionToExecute));
    }
    if (executionResult.status() == TransactionStatus.ABORTED) {
      observers.forEach(observer -> observer.observeAbort(transactionToExecute));
    }
    // Add all unblocked transactions to the queue
    transactionQueue.addAll(executionResult.unblockedTransactions());
    return !transactionQueue.isEmpty();
  }
}
