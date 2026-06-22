package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkState;

import java.nio.file.Path;
import java.util.Optional;
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
   * Path to a VitruvOCL constraints file to check after applying a transaction's operations,
   * or empty if no consistency check should be performed.
   */
  private final Optional<Path> constraintsFile;

  /**
   * Creates a new {@link C2PLScheduler} that does not perform consistency checks.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   * @param maximumConcurrentNumberOfThreads int
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment,
                       int maximumConcurrentNumberOfThreads) {
    this(multiModelEnvironment, maximumConcurrentNumberOfThreads, Optional.empty());
  }

  /**
   * Creates a new {@link C2PLScheduler}, checking consistency against {@code constraintsFile}
   * after applying each transaction's operations.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   * @param maximumConcurrentNumberOfThreads int
   * @param constraintsFile path to a VitruvOCL constraints file, or empty to skip the check
   */
  public C2PLScheduler(InternalVirtualModel multiModelEnvironment,
                       int maximumConcurrentNumberOfThreads,
                       Optional<Path> constraintsFile) {
    super(multiModelEnvironment, maximumConcurrentNumberOfThreads);
    this.constraintsFile = constraintsFile;
  }

  @Override
  protected C2PLThread createNewExecutorThread(TransactionState<EObject> newTransaction) {
    return new C2PLThread(newTransaction, observers, multiModelEnvironment, lockManager, constraintsFile);
  }
}
