package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.transactions.management.locking.Lock;
import tools.vitruv.transactions.management.locking.S2PLLockManager;

/**
 * Helpers for tests of the {@link S2PLLockManager}, whose lock requests may wait: requests run on
 * an executor, and the tests observe whether they wait or complete.
 */
final class S2PLTestSupport {
  static final long TIMEOUT_SECONDS = 10;

  private final S2PLLockManager<EObject> lockManager;
  private final ExecutorService executor;

  S2PLTestSupport(S2PLLockManager<EObject> lockManager, ExecutorService executor) {
    this.lockManager = lockManager;
    this.executor = executor;
  }

  static TransactionalChangeImpl<EObject> newChange() {
    return new TransactionalChangeImpl<>(List.of(
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
            CommonCreatorClasses.ROOT)));
  }

  TransactionState<EObject> newTransaction() {
    var transaction = lockManager.submitTransaction(newChange());
    transaction.setToRunning();
    return transaction;
  }

  /** Waits until {@code transaction} waits for another transaction (has wait-for edges). */
  void awaitWaiting(TransactionState<EObject> transaction) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (lockManager.getWaitsFor(transaction).isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("transaction does not wait");
      }
      Thread.sleep(5);
    }
  }

  Future<?> acquireInBackground(TransactionState<EObject> transaction,
      List<? extends Lock<EObject>> locks) {
    return executor.submit(() -> {
      lockManager.acquireLocks(transaction, locks);
      return null;
    });
  }

  static void assertCompletes(Future<?> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  static void assertStillWaiting(Future<?> future) throws InterruptedException {
    Thread.sleep(50);
    assertFalse(future.isDone(), "request should still wait");
  }
}
