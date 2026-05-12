package tools.vitruv.transactions.management.locking;

import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;
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
   * Creates a new {@link C2PLScheduler}.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment) {
    super(multiModelEnvironment);
  }

  @Override
  protected C2PLThread createNewExecutorThread(TransactionState<EObject> newTransaction) {
    return new C2PLThread(newTransaction, observers, multiModelEnvironment, lockManager);
  }

  /**
   * Admits a new transaction for {@code change}, starts its execution,
   * and reports this to all observers.
   *
   * @param change - {@link VitruviusChange}
   * @return {@link TransactionState}
   */
  @Override
  public TransactionState<EObject> admitTransaction(VitruviusChange<EObject> change) {
    var newTransaction = lockManager.submitTransaction(change);
    observers.forEach(observer -> observer.observeAdmission(newTransaction));
    var transactionThread = createNewExecutorThread(newTransaction);
    transactionThreadService.submit(transactionThread);
    return newTransaction;
  }
}
