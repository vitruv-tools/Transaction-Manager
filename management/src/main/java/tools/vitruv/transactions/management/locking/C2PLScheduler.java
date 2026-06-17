package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkState;

import org.eclipse.emf.ecore.EObject;
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
}
