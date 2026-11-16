package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.vitruv.transactions.management.locking.LockMode.EXCLUSIVE;
import static tools.vitruv.transactions.management.locking.LockMode.INTENTION_SHARED;
import static tools.vitruv.transactions.management.locking.LockMode.SHARED;
import static tools.vitruv.transactions.management.locking.LockMode.SHARED_INTENSIONAL_EXCLUSIVE;

import allElementTypes.AllElementTypesPackage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.transactions.management.locking.DeadlockVictimException;
import tools.vitruv.transactions.management.locking.HierarchicalLockComputer;
import tools.vitruv.transactions.management.locking.KeyLock;
import tools.vitruv.transactions.management.locking.Lock;
import tools.vitruv.transactions.management.locking.LockKeyProvider;
import tools.vitruv.transactions.management.locking.LockKeyProvider.KeyRequest;
import tools.vitruv.transactions.management.locking.LockManager;
import tools.vitruv.transactions.management.locking.LockMode;

/**
 * Tests for the lock modes, strict 2PL with deadlock detection, and hierarchical (type-level)
 * locks of the {@link LockManager}.
 */
class StrictTwoPhaseLockingTest {
  private static final long TIMEOUT_SECONDS = 10;
  private static final String ROOT_INTEGER_KEY =
      HierarchicalLockComputer.featureKey(CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE);
  private static final String ROOT_CLASS_KEY =
      HierarchicalLockComputer.classKey(AllElementTypesPackage.eINSTANCE.getRoot());

  private LockManager<EObject> lockManager;
  private ExecutorService executor;

  @BeforeEach
  void setup() {
    lockManager = new LockManager<>();
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private TransactionalChangeImpl<EObject> newChange() {
    return new TransactionalChangeImpl<>(List.of(
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
            CommonCreatorClasses.ROOT)));
  }

  private TransactionState<EObject> newTransaction() {
    var transaction = lockManager.submitTransaction(newChange());
    transaction.setToRunning();
    return transaction;
  }

  private static KeyLock<EObject> key(String key, LockMode mode) {
    return new KeyLock<>(key, mode);
  }

  /** Waits until {@code transaction} waits for another transaction (has wait-for edges). */
  private void awaitWaiting(TransactionState<EObject> transaction) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (lockManager.getWaitsFor(transaction).isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("transaction does not wait");
      }
      Thread.sleep(5);
    }
  }

  private Future<?> acquireInBackground(TransactionState<EObject> transaction,
      List<? extends Lock<EObject>> locks) {
    return executor.submit(() -> {
      lockManager.acquireLocks(transaction, locks);
      return null;
    });
  }

  private static void assertCompletes(Future<?> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void assertStillWaiting(Future<?> future) throws InterruptedException {
    Thread.sleep(50);
    assertFalse(future.isDone(), "request should still wait");
  }

  // ------------------------------------------------------------------ modes

  @Test
  void testCompatibilityMatrix() {
    LockMode ix = SHARED_INTENSIONAL_EXCLUSIVE;
    Map<LockMode, Set<LockMode>> compatible = Map.of(
        INTENTION_SHARED, Set.of(INTENTION_SHARED, ix, SHARED),
        ix, Set.of(INTENTION_SHARED, ix),
        SHARED, Set.of(INTENTION_SHARED, SHARED),
        EXCLUSIVE, Set.of());
    for (LockMode a : LockMode.values()) {
      for (LockMode b : LockMode.values()) {
        assertEquals(compatible.get(a).contains(b), a.isCompatibleWith(b), a + " vs " + b);
      }
    }
    assertEquals(EXCLUSIVE, LockMode.highestLockMode(ix, SHARED));
    assertEquals(SHARED, LockMode.highestLockMode(INTENTION_SHARED, SHARED));
    assertEquals(ix, LockMode.highestLockMode(ix, INTENTION_SHARED));
    assertEquals(INTENTION_SHARED, SHARED.intention());
    assertEquals(ix, EXCLUSIVE.intention());
  }

  @Test
  void testSharedReadersBlockWriter() throws InterruptedException {
    var t1 = newTransaction();
    var t2 = newTransaction();
    var t3 = newTransaction();
    assertTrue(lockManager.testLock(key("k", SHARED), t1).isEmpty());
    assertTrue(lockManager.testLock(key("k", SHARED), t2).isEmpty());
    assertEquals(Set.of(t1, t2), lockManager.testLock(key("k", EXCLUSIVE), t3).orElseThrow());
  }

  @Test
  void testUpgradeIsBlockedOnlyByOthers() throws InterruptedException {
    var t1 = newTransaction();
    var t2 = newTransaction();
    assertTrue(lockManager.testLock(key("k", INTENTION_SHARED), t1).isEmpty());
    assertTrue(lockManager.testLock(key("k", INTENTION_SHARED), t2).isEmpty());
    assertEquals(Set.of(t2), lockManager.testLock(key("k", EXCLUSIVE), t1).orElseThrow());
    // sole holder upgrades
    lockManager.releaseAllLocks(t2, true);
    assertTrue(lockManager.testLock(key("k", EXCLUSIVE), t1).isEmpty());
    assertEquals(Set.of(key("k", EXCLUSIVE)), lockManager.getLocksHeldBy(t1));
    assertEquals(EXCLUSIVE, lockManager.getLocksHeldBy(t1).iterator().next().getMode());
  }

  // ------------------------------------------------------------------ strict 2PL, deadlocks

  @Test
  void testWaitingRequestIsGrantedAfterHolderEnds() throws Exception {
    var t1 = newTransaction();
    var t2 = newTransaction();
    lockManager.acquireLocks(t1, List.of(key("a", EXCLUSIVE)));
    var request = acquireInBackground(t2, List.of(key("a", SHARED)));
    assertStillWaiting(request);
    lockManager.endTransaction(t1, true);
    assertCompletes(request);
    assertEquals(Set.of(key("a", SHARED)), lockManager.getLocksHeldBy(t2));
  }

  @Test
  void testDeadlockRequesterIsYoungest() throws Exception {
    var older = newTransaction();
    var younger = newTransaction();
    lockManager.acquireLocks(older, List.of(key("a", EXCLUSIVE)));
    lockManager.acquireLocks(younger, List.of(key("b", EXCLUSIVE)));
    var olderRequest = acquireInBackground(older, List.of(key("b", EXCLUSIVE)));
    awaitWaiting(older);
    assertStillWaiting(olderRequest);

    var victim = assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(younger, List.of(key("a", EXCLUSIVE))));
    assertEquals(Set.of(older, younger), Set.copyOf(victim.getCycle()));
    // the victim keeps its locks until it ends
    assertStillWaiting(olderRequest);
    lockManager.endTransaction(younger, false);
    assertCompletes(olderRequest);
    assertEquals(Set.of(key("a", EXCLUSIVE), key("b", EXCLUSIVE)),
        lockManager.getLocksHeldBy(older));
  }

  @Test
  void testDeadlockVictimIsWokenUp() throws Exception {
    var older = newTransaction();
    var younger = newTransaction();
    lockManager.acquireLocks(older, List.of(key("a", EXCLUSIVE)));
    lockManager.acquireLocks(younger, List.of(key("b", EXCLUSIVE)));
    // the younger one waits first, the older one closes the cycle
    var youngerRequest = acquireInBackground(younger, List.of(key("a", EXCLUSIVE)));
    awaitWaiting(younger);
    var olderRequest = acquireInBackground(older, List.of(key("b", EXCLUSIVE)));

    var failure = assertThrows(ExecutionException.class,
        () -> youngerRequest.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof DeadlockVictimException);
    assertStillWaiting(olderRequest);
    lockManager.endTransaction(younger, false);
    assertCompletes(olderRequest);
  }

  @Test
  void testThreeTransactionCycle() throws Exception {
    var t1 = newTransaction();
    var t2 = newTransaction();
    var t3 = newTransaction();
    lockManager.acquireLocks(t1, List.of(key("a", EXCLUSIVE)));
    lockManager.acquireLocks(t2, List.of(key("b", EXCLUSIVE)));
    lockManager.acquireLocks(t3, List.of(key("c", SHARED)));
    var r1 = acquireInBackground(t1, List.of(key("b", SHARED)));
    awaitWaiting(t1);
    var r2 = acquireInBackground(t2, List.of(key("c", EXCLUSIVE)));
    awaitWaiting(t2);
    var victim = assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(t3, List.of(key("a", INTENTION_SHARED))));
    assertEquals(3, victim.getCycle().size());
    lockManager.endTransaction(t3, false);
    assertCompletes(r2);
    lockManager.endTransaction(t2, true);
    assertCompletes(r1);
  }

  @Test
  void testRestartedVictimKeepsItsAge() throws Exception {
    var older = newTransaction();
    var victimChange = newChange();
    var victim = lockManager.submitTransaction(victimChange);
    victim.setToRunning();
    lockManager.acquireLocks(older, List.of(key("a", EXCLUSIVE)));
    lockManager.acquireLocks(victim, List.of(key("b", EXCLUSIVE)));
    var olderRequest = acquireInBackground(older, List.of(key("b", EXCLUSIVE)));
    awaitWaiting(older);
    assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(victim, List.of(key("a", EXCLUSIVE))));
    lockManager.endTransaction(victim, false);
    assertCompletes(olderRequest);
    lockManager.endTransaction(older, true);

    // restart the victim; a transaction submitted after the first attempt is younger
    var newer = newTransaction();
    var restarted = lockManager.submitTransaction(victimChange);
    restarted.setToRunning();
    lockManager.acquireLocks(restarted, List.of(key("a", EXCLUSIVE)));
    lockManager.acquireLocks(newer, List.of(key("b", EXCLUSIVE)));
    var restartedRequest = acquireInBackground(restarted, List.of(key("b", EXCLUSIVE)));
    awaitWaiting(restarted);
    assertThrows(DeadlockVictimException.class,
        () -> lockManager.acquireLocks(newer, List.of(key("a", EXCLUSIVE))));
    lockManager.endTransaction(newer, false);
    assertCompletes(restartedRequest);
  }

  // ------------------------------------------------------------------ hierarchical locks

  private static LockKeyProvider<EObject> provider(KeyRequest... requests) {
    return change -> List.of(requests);
  }

  private static LockKeyProvider<EObject> noKeys() {
    return change -> List.of();
  }

  private static Map<String, LockMode> keyLocks(List<Lock<EObject>> locks) {
    return locks.stream().filter(KeyLock.class::isInstance)
        .collect(Collectors.toMap(l -> ((KeyLock<EObject>) l).getKey(), Lock::getMode));
  }

  @Test
  void testInstanceLocksImplyTypeLevelIntentions() {
    EChange<EObject> change =
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
            CommonCreatorClasses.ROOT);
    var locks = new HierarchicalLockComputer(noKeys()).computeLocksFor(change);
    var keys = keyLocks(locks);
    assertEquals(SHARED_INTENSIONAL_EXCLUSIVE, keys.get(ROOT_INTEGER_KEY));
    assertEquals(SHARED_INTENSIONAL_EXCLUSIVE, keys.get(ROOT_CLASS_KEY));
    assertEquals(SHARED_INTENSIONAL_EXCLUSIVE,
        keys.get(HierarchicalLockComputer.metamodelKey(AllElementTypesPackage.eINSTANCE.getRoot())));
    // the provider's read of all roots and the instance write merge into an exclusive lock
    var merged = keyLocks(new HierarchicalLockComputer(provider(new KeyRequest(ROOT_CLASS_KEY,
        SHARED))).computeLocksFor(change));
    assertEquals(EXCLUSIVE, merged.get(ROOT_CLASS_KEY));
  }

  @Test
  void testTypeLevelWriteConflictsWithInstanceWrite() throws Exception {
    // t1's reactions write the integer attribute of any root
    var writer = new HierarchicalLockComputer(
        provider(new KeyRequest(ROOT_INTEGER_KEY, EXCLUSIVE),
            new KeyRequest("mm:" + AllElementTypesPackage.eNS_URI, SHARED_INTENSIONAL_EXCLUSIVE)));
    var plain = new HierarchicalLockComputer(noKeys());
    var t1 = newTransaction();
    var t2 = newTransaction();
    var t3 = newTransaction();
    lockManager.acquireLocks(t1,
        writer.computeLocksFor(CommonCreatorClasses.getIdentifiedInsertReferenceChange()));

    // t2 changes that attribute of ROOT: waits for t1
    var conflicting = acquireInBackground(t2, plain.computeLocksFor(
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
            CommonCreatorClasses.ROOT)));
    assertStillWaiting(conflicting);

    // t3 changes another attribute of ROOT: compatible
    EChange<EObject> otherAttribute = CommonCreatorClasses.E_CHANGE_FACTORY
        .createReplaceSingleAttributeChange((EObject) CommonCreatorClasses.ROOT,
            CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE_2, 0, 1);
    assertCompletes(acquireInBackground(t3, plain.computeLocksFor(otherAttribute)));
    awaitWaiting(t2);

    lockManager.endTransaction(t1, true);
    assertCompletes(conflicting);
  }

  @Test
  void testTypeLevelReadOfAllInstancesConflictsWithInstanceWrite() throws Exception {
    var plain = new HierarchicalLockComputer(noKeys());
    var t1 = newTransaction();
    var t2 = newTransaction();
    var t3 = newTransaction();
    lockManager.acquireLocks(t1, plain.computeLocksFor(
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
            CommonCreatorClasses.ROOT)));
    // reading all roots (e.g. a reaction iterating over them) waits for the instance write
    var blocked = lockManager.testLock(key(ROOT_CLASS_KEY, SHARED), t2);
    assertEquals(Set.of(t1), blocked.orElseThrow());
    lockManager.endTransaction(t1, true);
    // two readers of all roots do not conflict
    assertTrue(lockManager.testLock(key(ROOT_CLASS_KEY, SHARED), t2).isEmpty());
    assertTrue(lockManager.testLock(key(ROOT_CLASS_KEY, SHARED), t3).isEmpty());
  }
}
