package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.vitruv.transactions.management.S2PLTestSupport.assertCompletes;
import static tools.vitruv.transactions.management.S2PLTestSupport.assertStillWaiting;
import static tools.vitruv.transactions.management.locking.LockMode.EXCLUSIVE;
import static tools.vitruv.transactions.management.locking.LockMode.INTENTION_SHARED;
import static tools.vitruv.transactions.management.locking.LockMode.SHARED;

import allElementTypes.AllElementTypesFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.vitruv.transactions.management.locking.ElementLock;
import tools.vitruv.transactions.management.locking.LockMode;
import tools.vitruv.transactions.management.locking.ModelLevelLockProvider;
import tools.vitruv.transactions.management.locking.S2PLLockManager;
import tools.vitruv.transactions.management.locking.deadlock.DeadlockDetector;
import tools.vitruv.transactions.management.locking.deadlock.DeadlockVictimException;
import tools.vitruv.transactions.management.locking.deadlock.VictimSelectionStrategy;

/**
 * Deadlock detection and resolution of the {@link S2PLLockManager}: cycles in the wait-for graph,
 * victim selection strategies, and the age of transactions across aborts.
 */
class DeadlockTest {
  private final EObject a = AllElementTypesFactory.eINSTANCE.createRoot();
  private final EObject b = AllElementTypesFactory.eINSTANCE.createRoot();
  private final EObject c = AllElementTypesFactory.eINSTANCE.createRoot();

  private S2PLLockManager<EObject> lockManager;
  private ExecutorService executor;
  private S2PLTestSupport support;

  @BeforeEach
  void setup() {
    use(new S2PLLockManager<>());
  }

  private void use(S2PLLockManager<EObject> manager) {
    lockManager = manager;
    if (executor != null) {
      executor.shutdownNow();
    }
    executor = Executors.newCachedThreadPool();
    support = new S2PLTestSupport(lockManager, executor);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private static ElementLock<EObject> lock(EObject element, LockMode mode) {
    return new ElementLock<>(element, mode);
  }

  @Test
  void testDeadlockRequesterIsYoungest() throws Exception {
    var older = support.newTransaction();
    var younger = support.newTransaction();
    lockManager.acquireLocks(older, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(younger, List.of(lock(b, EXCLUSIVE)));
    var olderRequest = support.acquireInBackground(older, List.of(lock(b, EXCLUSIVE)));
    support.awaitWaiting(older);
    assertStillWaiting(olderRequest);

    var victim = assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(younger, List.of(lock(a, EXCLUSIVE))));
    assertEquals(Set.of(older, younger), Set.copyOf(victim.getCycle()));
    // the victim keeps its locks until it ends
    assertStillWaiting(olderRequest);
    lockManager.endTransaction(younger, false);
    assertCompletes(olderRequest);
    assertEquals(Set.of(lock(a, EXCLUSIVE), lock(b, EXCLUSIVE)),
        lockManager.getLocksHeldBy(older));
  }

  @Test
  void testDeadlockVictimIsWokenUp() throws Exception {
    var older = support.newTransaction();
    var younger = support.newTransaction();
    lockManager.acquireLocks(older, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(younger, List.of(lock(b, EXCLUSIVE)));
    // the younger one waits first, the older one closes the cycle
    var youngerRequest = support.acquireInBackground(younger, List.of(lock(a, EXCLUSIVE)));
    support.awaitWaiting(younger);
    var olderRequest = support.acquireInBackground(older, List.of(lock(b, EXCLUSIVE)));

    var failure = assertThrows(ExecutionException.class,
        () -> youngerRequest.get(S2PLTestSupport.TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof DeadlockVictimException);
    assertStillWaiting(olderRequest);
    lockManager.endTransaction(younger, false);
    assertCompletes(olderRequest);
  }

  @Test
  void testThreeTransactionCycle() throws Exception {
    var t1 = support.newTransaction();
    var t2 = support.newTransaction();
    var t3 = support.newTransaction();
    lockManager.acquireLocks(t1, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(t2, List.of(lock(b, EXCLUSIVE)));
    lockManager.acquireLocks(t3, List.of(lock(c, SHARED)));
    var r1 = support.acquireInBackground(t1, List.of(lock(b, SHARED)));
    support.awaitWaiting(t1);
    var r2 = support.acquireInBackground(t2, List.of(lock(c, EXCLUSIVE)));
    support.awaitWaiting(t2);
    var victim = assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(t3, List.of(lock(a, INTENTION_SHARED))));
    assertEquals(3, victim.getCycle().size());
    lockManager.endTransaction(t3, false);
    assertCompletes(r2);
    lockManager.endTransaction(t2, true);
    assertCompletes(r1);
  }

  @Test
  void testRestartedVictimKeepsItsAge() throws Exception {
    var older = support.newTransaction();
    var victimChange = S2PLTestSupport.newChange();
    var victim = lockManager.submitTransaction(victimChange);
    victim.setToRunning();
    lockManager.acquireLocks(older, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(victim, List.of(lock(b, EXCLUSIVE)));
    var olderRequest = support.acquireInBackground(older, List.of(lock(b, EXCLUSIVE)));
    support.awaitWaiting(older);
    assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(victim, List.of(lock(a, EXCLUSIVE))));
    lockManager.endTransaction(victim, false);
    assertCompletes(olderRequest);
    lockManager.endTransaction(older, true);

    // restart the victim; a transaction submitted after the first attempt is younger
    var newer = support.newTransaction();
    var restarted = lockManager.submitTransaction(victimChange);
    restarted.setToRunning();
    lockManager.acquireLocks(restarted, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(newer, List.of(lock(b, EXCLUSIVE)));
    var restartedRequest = support.acquireInBackground(restarted, List.of(lock(b, EXCLUSIVE)));
    support.awaitWaiting(restarted);
    assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(newer, List.of(lock(a, EXCLUSIVE))));
    lockManager.endTransaction(newer, false);
    assertCompletes(restartedRequest);
  }

  @Test
  void testRequesterDiesStrategy() throws Exception {
    use(new S2PLLockManager<>(new ModelLevelLockProvider<>(),
        VictimSelectionStrategy.requesterDies()));
    var older = support.newTransaction();
    var younger = support.newTransaction();
    lockManager.acquireLocks(older, List.of(lock(a, EXCLUSIVE)));
    lockManager.acquireLocks(younger, List.of(lock(b, EXCLUSIVE)));
    var youngerRequest = support.acquireInBackground(younger, List.of(lock(a, EXCLUSIVE)));
    support.awaitWaiting(younger);
    // the older one closes the cycle and, as requester, dies (youngest dies would spare it)
    assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(older, List.of(lock(b, EXCLUSIVE))));
    lockManager.endTransaction(older, false);
    assertCompletes(youngerRequest);
  }

  @Test
  void testAgeIsForgottenOnCommitAndCancelButKeptForResubmission() throws Exception {
    var committed = support.newTransaction();
    var aborted = support.newTransaction();
    var cancelled = support.newTransaction();
    lockManager.endTransaction(committed, true);
    lockManager.endTransaction(aborted, false);
    lockManager.cancelTransaction(cancelled);
    assertFalse(lockManager.keepsAgeOf(committed));
    assertTrue(lockManager.keepsAgeOf(aborted), "may be resubmitted");
    assertFalse(lockManager.keepsAgeOf(cancelled), "will not be resubmitted");
    assertFalse(lockManager.isActive(cancelled));
  }

  @Test
  void testDetectorFindsCyclesAndAsksTheStrategy() {
    var t1 = support.newTransaction();
    var t2 = support.newTransaction();
    var t3 = support.newTransaction();
    var detector = new DeadlockDetector<EObject>(VictimSelectionStrategy.youngestDies());
    detector.admit(t1);
    detector.admit(t2);
    detector.admit(t3);
    assertTrue(detector.waitFor(t1, Set.of(t2)).isEmpty());
    assertTrue(detector.waitFor(t2, Set.of(t3)).isEmpty());
    assertEquals(t3, detector.waitFor(t3, Set.of(t1)).orElseThrow(), "the youngest");
    assertEquals(List.of(t3, t1, t2), detector.findCycle(t3).orElseThrow());
    assertThrows(DeadlockVictimException.class, () -> detector.throwIfVictim(t3));
    assertTrue(detector.findCycle(t1).isEmpty(), "the victim's edges are gone");
    assertTrue(detector.waitFor(t1, Set.of()).isEmpty());
    assertEquals(Set.of(), detector.waitsFor(t1));
  }
}
