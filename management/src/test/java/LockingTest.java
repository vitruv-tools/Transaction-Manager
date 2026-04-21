import lombok.Getter;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.change.testutils.metamodels.AllElementTypesCreators;
import tools.vitruv.transactions.management.Transaction;
import tools.vitruv.transactions.management.locking.*;

import java.util.*;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.transactions.management.TransactionStatus.*;

public class LockingTest {

    private LockManager<EObject> lockManager;

    @BeforeEach
    void setup() {
        lockManager = new LockManager<>();
    }

    /**
     * Tests that a {@link LockManager} determines the required {@link Lock}s for given
     * types of {@link EChange}.
     */
    @Test
    void testCorrectComputationOfLocks() {
        // CreateEChange
        var createRootChange = CommonCreatorClasses.getCreateRootEObjectChange();
        var locksForCreateRootChange = LockComputer.computeLocksFor(createRootChange);
        assertEquals(1, locksForCreateRootChange.size());
        var createLock = (ElementLock<EObject>) locksForCreateRootChange.get(0);
        assertEquals(CommonCreatorClasses.ROOT, createLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, createLock.getMode());

        // DeleteEChange
        var deleteRootChange = CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT);
        var locksForDeleteRootChange = LockComputer.computeLocksFor(deleteRootChange);

        assertEquals(1, locksForDeleteRootChange.size());
        var deleteLock = (ElementLock<EObject>) locksForDeleteRootChange.get(0);
        assertEquals(CommonCreatorClasses.ROOT, deleteLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, deleteLock.getMode());

        // ReplaceEAttributeEChange
        var setRootEAttributeChange = CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT);
        var locksForReplaceAttributeChange = LockComputer.computeLocksFor(setRootEAttributeChange);
        assertEquals(2, locksForReplaceAttributeChange.size());
        var lockOnRoot = (ElementLock<EObject>) locksForReplaceAttributeChange.get(0);
        assertEquals(CommonCreatorClasses.ROOT, lockOnRoot.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRoot.getMode());
        var lockOnAttribute = (FeatureLock<EObject>) locksForReplaceAttributeChange.get(1);
        assertEquals(CommonCreatorClasses.ROOT, lockOnAttribute.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnAttribute.getMode());
        assertEquals(CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE, lockOnAttribute.getFeature());

        // InsertReferenceEChange
        var insertRootEReferenceChange = CommonCreatorClasses.getIdentifiedInsertReferenceChange();

        var locksForInsertReferenceChange = LockComputer.computeLocksFor(insertRootEReferenceChange);
        assertEquals(3, locksForInsertReferenceChange.size());
        var lockOnRootInsert = (ElementLock<EObject>) locksForInsertReferenceChange.get(0);
        assertEquals(CommonCreatorClasses.ROOT, lockOnRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootInsert.getMode());
        var lockOnNonRootInsert = (ElementLock<EObject>) locksForInsertReferenceChange.get(1);
        assertEquals(CommonCreatorClasses.NON_ROOT, lockOnNonRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootInsert.getMode());
        var lockOnReferenceInsert = (FeatureLock<EObject>) locksForInsertReferenceChange.get(2);
        assertEquals(CommonCreatorClasses.ROOT, lockOnReferenceInsert.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceInsert.getMode());
        assertEquals(CommonCreatorClasses.ROOT_NON_ROOT_E_REFERENCE, lockOnReferenceInsert.getFeature());

        // RemoveReferenceEChange
        var removeRootEReferenceChange = CommonCreatorClasses.getIdentifiedRemoveEReferenceChange();

        var locksForRemoveReferenceChange = LockComputer.computeLocksFor(removeRootEReferenceChange);
        assertEquals(3, locksForRemoveReferenceChange.size());
        var lockOnRootRemove = (ElementLock<EObject>) locksForRemoveReferenceChange.get(0);
        assertEquals(CommonCreatorClasses.ROOT, lockOnRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootRemove.getMode());
        var lockOnNonRootRemove = (ElementLock<EObject>) locksForRemoveReferenceChange.get(1);
        assertEquals(CommonCreatorClasses.NON_ROOT, lockOnNonRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootRemove.getMode());
        var lockOnReferenceRemove = (FeatureLock<EObject>) locksForRemoveReferenceChange.get(2);
        assertEquals(CommonCreatorClasses.ROOT, lockOnReferenceRemove.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceRemove.getMode());
        assertEquals(CommonCreatorClasses.ROOT_NON_ROOT_E_REFERENCE, lockOnReferenceRemove.getFeature());
    }

    /**
     * Empty transactions are not accepted by a {@link LockManager}.
     */
    @Test
    void testEmptyTransactionsDoNotWork() {
        var emptyVitruviusChange = new TransactionalChangeImpl<EObject>(
            List.of()
        );
        assertThrows(IllegalStateException.class, () -> lockManager.submitTransaction(emptyVitruviusChange));
    }

    /**
     * Tests that one transaction only "succeeds", i.e.
     * it can be submitted in a STARTING state, set to running,
     * and all four of its operations can be "applied".
     * After this, it can be commited.
     */
    @Test
    void testLifecycleOfOneTransaction() {
        List<? extends EChange<EObject>> changes = List.of(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
                CommonCreatorClasses.ROOT
            ),
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange(),
            CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT)
        );
        var vitruviusChange = new TransactionalChangeImpl<>(changes);
        var lockManager = new LockManager<EObject>();

        // Submit transaction
        var transaction = lockManager.submitTransaction(vitruviusChange);
        assertEquals(STARTED, transaction.getStatus());
        assertTrue(transaction.hasOperationsToExecute());
        // Run transaction
        transaction.setToRunning();

        for (int i = 0; i < changes.size(); i++) {
            lockManager.acquireLocksForNextOperation(transaction);
        }
        // All operations have been processed
        assertFalse(transaction.hasOperationsToExecute());

        // Check locks
        var locksHeldByTransaction = lockManager.getLocksHeldBy(transaction);
        assertLock(locksHeldByTransaction, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, null);
        assertLock(locksHeldByTransaction, CommonCreatorClasses.NON_ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locksHeldByTransaction, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE);
        assertLock(locksHeldByTransaction, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, CommonCreatorClasses.ROOT_NON_ROOT_E_REFERENCE);

        // Unlock everything
        for (var lock: locksHeldByTransaction) {
            lockManager.unsetLock(lock, transaction);
        }
        // Commit
        lockManager.commit(transaction);
        assertEquals(COMMITED, transaction.getStatus());
    }

    @Test
    void testsForNoLockConflicts() {
        assertLockCompatibility(
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT)
        );
        assertLockCompatibility(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange()
        );
        assertLockCompatibility(
            CommonCreatorClasses.getCreateRootEObjectChange(),
            CommonCreatorClasses.E_CHANGE_FACTORY.createDeleteEObjectChange(CommonCreatorClasses.NON_ROOT)
        );
        assertLockCompatibility(
            CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.E_CHANGE_FACTORY.createCreateEObjectChange(CommonCreatorClasses.NON_ROOT)
        );
        assertLockCompatibility(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
                CommonCreatorClasses.ROOT,
                CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE_2,
                0,
                6477
            )
        );
        assertLockCompatibility(
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.E_CHANGE_FACTORY.createRemoveReferenceChange(
                CommonCreatorClasses.ROOT,
                CommonCreatorClasses.ROOT_NON_ROOT_E_REFERENCE_2,
                CommonCreatorClasses.NON_ROOT,
                0
            )
        );
        assertLockCompatibility(
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.E_CHANGE_FACTORY.createDeleteEObjectChange(
                AllElementTypesCreators.aet.ValueBased()
            )
        );
    }

    @Test
    void testsForLockConflicts() {
        assertLockConflict(
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange(),
            CommonCreatorClasses.getIdentifiedInsertReferenceChange()
        );
        assertLockConflict(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT)
        );
        assertLockConflict(
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange()
        );
        assertLockConflict(
            CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange()
        );
    }

    @Test
    void testSingleTransactionUnlocking() {
        // Create transaction, with two operations
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(CommonCreatorClasses.getIdentifiedInsertReferenceChange(), CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT))
            )
        );
        // Create second transaction
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
                    CommonCreatorClasses.ROOT
                ), CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT))
            )
        );
        // T1 acquires lock for op1
        transaction1.setToRunning();
        assertTrue(lockManager.acquireLocksForNextOperation(transaction1).isEmpty());
        // T2 acquires lock for op1, but not for op2
        transaction2.setToRunning();
        assertTrue(lockManager.acquireLocksForNextOperation(transaction2).isEmpty());
        assertFalse(lockManager.acquireLocksForNextOperation(transaction2).isEmpty());

        // Get current locks of t1, expect 3 of them
        var currentLocks = lockManager.getLocksHeldBy(transaction1);
        assertEquals(3, currentLocks.size());

        // Release each lock for the first operation
        for (var lockOfT1Op1: currentLocks) {
            lockManager.unsetLock(lockOfT1Op1, transaction1);
        }
        // Transaction 1 must not hold locks now
        assertTrue(lockManager.getLocksHeldBy(transaction1).isEmpty());
        // Transaction 2 retains its locks
        var locksOfT2 = lockManager.getLocksHeldBy(transaction2);
        assertEquals(2, locksOfT2.size());
        assertLock(locksOfT2, CommonCreatorClasses.ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locksOfT2, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE);
        // Transaction 2 can proceed with op2
        transaction2.setToRunning();
        assertTrue(lockManager.acquireLocksForNextOperation(transaction2).isEmpty());

        // Transaction 1 should fail to acquire further locks
        assertThrows(IllegalArgumentException.class,
            () -> lockManager.acquireLocksForNextOperation(transaction1));
    }

    void assertLockConflict(EChange<EObject> change1, EChange<EObject> change2) {
        setup();
        // Create two transactions
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(List.of(change1))
        );
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(List.of(change2))
        );
        // Run
        transaction1.setToRunning();
        transaction2.setToRunning();
        // Run op1 of Transaction 1
        assertTrue(lockManager.acquireLocksForNextOperation(transaction1).isEmpty());
        // Run op2 of Transaction 2, Transaction 1 causes conflict
        var blockingTransactions = lockManager.acquireLocksForNextOperation(transaction2);
        assertTrue(blockingTransactions.isPresent());
        assertEquals(1, blockingTransactions.get().size());
        assertEquals(transaction1, blockingTransactions.get().toArray()[0]);

        // Assert op2 does not hold locks afterward
        assertTrue(lockManager.getLocksHeldBy(transaction2).isEmpty());
    }

    void assertLockCompatibility(EChange<EObject> change1, EChange<EObject> change2) {
        setup();
        // Create two transactions
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(List.of(change1))
        );
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(List.of(change2))
        );
        transaction1.setToRunning();
        transaction2.setToRunning();
        // Run op1 of Transaction 1
        assertTrue(lockManager.acquireLocksForNextOperation(transaction1).isEmpty());
        // Run op2 of Transaction 2, expect no conflict
        var blockingTransactions = lockManager.acquireLocksForNextOperation(transaction2);
        assertTrue(blockingTransactions.isEmpty());
        // Assert Transaction 2 holds its requested locks
        assertEquals(
            lockManager.getLocksHeldBy(transaction2),
            new HashSet<>(LockComputer.computeLocksFor(change2))
        );
    }

    @Test
    void testLockUpgrade() {
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(
                    CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT),
                    CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT)
                )
            )
        );
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(
                    CommonCreatorClasses.getIdentifiedInsertReferenceChange()
                )
            )
        );

        // T1 -> first operation, SIX lock on ROOT and X lock on integer EAttribute
        transaction1.setToRunning();
        var locks = lockManager.computeNextLocksFor(transaction1);
        transaction1.markNextOperationAsExecutable();
        locks.forEach(lock -> lockManager.setLock(lock, transaction1));

        assertEquals(2, locks.size());
        assertLock(locks, CommonCreatorClasses.ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locks, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE);
        assertEquals(new HashSet<>(locks), lockManager.getLocksHeldBy(transaction1));

        // T1 -> second operation, X locks on ROOT and on EAttribute
        var locksOp2 = lockManager.computeNextLocksFor(transaction1);
        transaction1.markNextOperationAsExecutable();
        locksOp2.forEach(lock -> lockManager.setLock(lock, transaction1));
        var locksOfT1 = lockManager.getLocksHeldBy(transaction1);
        assertEquals(2, locksOfT1.size());
        assertLock(locksOfT1, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, null);
        assertLock(locksOfT1, CommonCreatorClasses.ROOT, LockMode.EXCLUSIVE, CommonCreatorClasses.ROOT_INTEGER_E_ATTRIBUTE);

        // T2 -> first operation, only second lock request should succeed
        transaction2.setToRunning();
        var locksT2Op1 = lockManager.computeNextLocksFor(transaction2);
        var conflicts = lockManager.testLock(locksT2Op1.get(0), transaction2);
        assertTrue(conflicts.isPresent());
        assertEquals(transaction1, conflicts.get().toArray()[0]);
        // Lock 2
        assertFalse(lockManager.testLock(locksT2Op1.get(1), transaction2).isPresent());
        // Lock 3
        conflicts = lockManager.testLock(locksT2Op1.get(2), transaction2);
        assertFalse(conflicts.isPresent());
    }

    @Test
    void testTransactionCorrectBehavior() {
        List<? extends EChange<EObject>> changes = List.of(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT),
            CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
            CommonCreatorClasses.getIdentifiedRemoveEReferenceChange(),
            CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT)
        );
        var vitruviusChange = new TransactionalChangeImpl<>(changes);

        var transaction = lockManager.submitTransaction(vitruviusChange);
        // Started transactions cannot return operations, only running ones
        assertThrows(IllegalStateException.class, transaction::peekNextOperation);

        // Set transaction to running
        transaction.setToRunning();
        // Transaction cannot be commited or set to running
        assertThrows(IllegalStateException.class, transaction::setToRunning);
        assertThrows(IllegalStateException.class, transaction::setToCommited);
        // Peek operation, allow double peek
        assertEquals(transaction.peekNextOperation(), transaction.peekNextOperation());
    }

    @Test
    void testC2PLBlockingResolutionStrategy() {
        var transactionThatBlocks = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(CommonCreatorClasses.getDeleteRootEObjectChange(CommonCreatorClasses.ROOT))
            )
        );

        var transactionThatIsBlocked = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(
                    CommonCreatorClasses.getIdentifiedRemoveEReferenceChange(),
                    CommonCreatorClasses.getIdentifiedInsertReferenceChange(),
                    CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(CommonCreatorClasses.ROOT)
                )
            )
        );

        // Execute operations 1 and 2 of transactionThatIsBlocked
        transactionThatIsBlocked.setToRunning();
        assertFalse(lockManager.acquireLocksForNextOperation(transactionThatIsBlocked).isPresent());
        assertFalse(lockManager.acquireLocksForNextOperation(transactionThatIsBlocked).isPresent());

        // Now pretend that we need to release locks
        transactionThatIsBlocked.setToBlocked();
        assertTrue(transactionThatIsBlocked.goToPreviousOperation());
        assertTrue(transactionThatIsBlocked.goToPreviousOperation());
        assertFalse(transactionThatIsBlocked.goToPreviousOperation());
        assertTrue(
            EcoreUtil.equals(
                CommonCreatorClasses.getIdentifiedRemoveEReferenceChange(),
                transactionThatIsBlocked.peekNextOperation())
        );
    }

    @Test
    void testMultipleLockCorrectness() throws InterruptedException{
        var numberOfThreads = 64;
        // Create Runnables
        var runnables = new ArrayList<LockRequestingRunnable>();
        for (int i = 0; i < numberOfThreads; i++) {
            runnables.add(new LockRequestingRunnable(lockManager));
        }
        // Start threads and wait for join
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < numberOfThreads; i++) {
            threads.add(new Thread(runnables.get(i), "LockRequestingRunnable " + i));
        }
        threads.forEach(Thread::start);
        for (var thread: threads) {
            try {
                thread.join();
            }
            catch (InterruptedException e) {
                throw e;
            }
        }

        // Exactly one runnable has locks at all.
        var lockingRunnables = runnables.stream()
            .filter(runnable -> !runnable.locks.isEmpty())
            .toList();
        assertEquals(1, lockingRunnables.size());
        // No transactions are currently blocked
        assertTrue(
            runnables.stream()
            .allMatch(runnable ->
                runnable.getTransaction().getStatus() != BLOCKED)
        );
    }

    private static class LockRequestingRunnable implements Runnable {
        @Getter
        private final Set<Lock<EObject>> locks = new HashSet<>();
        private final LockManager<EObject> manager;
        @Getter
        private Transaction<EObject> transaction;

        LockRequestingRunnable(LockManager<EObject> manager) {
            this.manager = manager;
        }

        @Override
        public void run() {
            try {
                var waitInMs = new Random().nextInt(50);
                System.out.println("Waiting for " + waitInMs + " milliseconds");
                sleep(waitInMs);
            }
            catch (InterruptedException ignored) {}
            var attributeChange = CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
                CommonCreatorClasses.ROOT
            );
            transaction = manager.submitTransaction(new TransactionalChangeImpl<>(
                List.of(attributeChange)
            ));
            transaction.setToRunning();
            // Request locks
            var blockingTransactions = manager.acquireLocksForNextOperation(transaction);
            if (blockingTransactions.isPresent()) {
                assertSame(BLOCKED, transaction.getStatus());
                return;
            }
            // Add locks
            locks.addAll(manager.getLocksHeldBy(transaction));
            // Release locks and finish
            locks.forEach(lock -> manager.unsetLock(lock, transaction));
            // Finish transaction
            manager.commit(transaction).forEach(transaction2 -> {
                if (transaction != transaction2) {
                    transaction2.setToRunning();
                }
            });
        }
    }

    /**
     * Checks the behavior of {@link LockMode}s and their strength.
     */
    @Test
    void testLockModeBehavior() {
        var lockModeX = LockMode.EXCLUSIVE;
        var lockModeSIX = LockMode.SHARED_INTENSIONAL_EXCLUSIVE;
        assertTrue(lockModeX.compareTo(lockModeSIX) > 0);
        assertTrue(lockModeSIX.compareTo(lockModeX) < 0);

        assertEquals(lockModeX, LockMode.highestLockMode(lockModeX, lockModeX));
        assertEquals(lockModeX, LockMode.highestLockMode(lockModeX, lockModeSIX));
        assertEquals(lockModeX, LockMode.highestLockMode(lockModeSIX, lockModeX));
        assertEquals(lockModeSIX, LockMode.highestLockMode(lockModeSIX, lockModeSIX));
    }

    /**
     * Test that some locks exist in {@code heldLocks}.
     */
    <E> void assertLock(Collection<Lock<E>> heldLocks,
                        E element, LockMode mode, EStructuralFeature feature) {
        assertTrue(heldLocks.stream()
            .filter(lock -> lock.getMode().equals(mode) && lock.getRoot() == element)
            .anyMatch(lock -> {
                if (feature != null && lock instanceof FeatureLock<E> featureLock) {
                    return featureLock.getFeature().equals(feature);
                }
                else {
                    return true;
                }
            }));
    }
}
