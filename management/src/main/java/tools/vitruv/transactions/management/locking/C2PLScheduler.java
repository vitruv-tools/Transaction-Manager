package tools.vitruv.transactions.management.locking;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
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
   * Creates a new {@link C2PLScheduler}.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   * @param maximumConcurrentNumberOfThreads int
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment,
                       int maximumConcurrentNumberOfThreads) {
    super(multiModelEnvironment, maximumConcurrentNumberOfThreads);
  }

  @Override
  protected C2PLThread createNewExecutorThread(TransactionState<EObject> newTransaction) {
    return new C2PLThread(newTransaction, observers, multiModelEnvironment, lockManager);
  }

  /**
   * Resubmits {@code transactionState} in case it was previously blocked, but now
   * has become unblocked.
   *
   * @param transactionState - {@link TransactionState}
   * @return {@link TransactionState}
   */
  private TransactionState<EObject> resubmitTransaction(
      TransactionState<EObject> transactionState) {
    var transactionThread = createNewExecutorThread(transactionState);
    startExecutionOf(transactionThread);
    return transactionState;
  }

  /**
   * Admits a new transaction for {@code change}, starts its execution,
   * and reports this to all observers.
   *
   * <p>Also takes care of restarting blocked transactions upon their unblock.
   *
   * @param change - {@link VitruviusChange}
   * @return {@link TransactionState}
   */
  @Override
  public TransactionState<EObject> admitTransaction(VitruviusChange<EObject> change) {
    synchronized (submittedTasksCounter) {
      submittedTasksCounter.incrementAndGet();
    }

    var newTransaction = lockManager.submitTransaction(change);
    observers.forEach(observer -> observer.observeAdmission(newTransaction));
    var transactionThread = createNewExecutorThread(newTransaction);
    startExecutionOf(transactionThread);
    return newTransaction;
  }

  /**
   * Starts the execution of {@code transactionThread}.
   *
   * <p>This method constructs a {@code CompletableFuture}, that, when {@code transactionThread}
   * has stopped executing, resubmits all unblocked transactions, thus ensuring that all transactions
   * terminate.
   *
   * @param transactionThread - {@link C2PLThread}
   */
  private void startExecutionOf(C2PLThread transactionThread) {
    CompletableFuture.supplyAsync(() -> {
      try {
        return transactionThread.call();
      } catch (InterruptedException e) {
        throw new CompletionException(e);
      }
    }, this.transactionThreadService)
        .thenAccept(result
            -> {
              result.unblockedTransactions().forEach(unblocked -> {
                synchronized (submittedTasksCounter) {
                  System.out.println("Resubmit " + unblocked);
                  submittedTasksCounter.incrementAndGet();
                }
                this.resubmitTransaction(unblocked);
              });
              synchronized (submittedTasksCounter) {
                submittedTasksCounter.decrementAndGet();
                submittedTasksCounter.notifyAll();
              }
            }
        );
  }
}
