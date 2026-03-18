import allElementTypes.AllElementTypesPackage;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.change.testutils.metamodels.AllElementTypesCreators;
import tools.vitruv.transactions.management.locking.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.transactions.management.locking.TransactionStatus.STARTED;

public class LockingTest {

    public static final Root ROOT = AllElementTypesCreators.aet.Root();
    public static final TypeInferringAtomicEChangeFactory E_CHANGE_FACTORY = TypeInferringAtomicEChangeFactory.getInstance();
    public static final EAttribute ROOT_INTEGER_E_ATTRIBUTE = AllElementTypesPackage.eINSTANCE
        .getRoot_SingleValuedEAttribute();
    public static final EAttribute ROOT_INTEGER_E_ATTRIBUTE_2 = AllElementTypesPackage.eINSTANCE
        .getRoot_SingleValuedPrimitiveTypeEAttribute();
    public static final EReference ROOT_NON_ROOT_E_REFERENCE = AllElementTypesPackage.eINSTANCE
        .getRoot_MultiValuedNonContainmentEReference();
    public static final EReference ROOT_NON_ROOT_E_REFERENCE_2 = AllElementTypesPackage.eINSTANCE
        .getRoot_MultiValuedUnorderedNonContainmentEReference();
    public static final NonRoot NON_ROOT = AllElementTypesCreators.aet.NonRoot();

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
        var createRootChange = getCreateRootEObjectChange();
        var locksForCreateRootChange = lockManager.computeLocksFor(createRootChange);
        assertEquals(1, locksForCreateRootChange.size());
        var createLock = (ElementLock<EObject>) locksForCreateRootChange.get(0);
        assertEquals(ROOT, createLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, createLock.getMode());

        // DeleteEChange
        var deleteRootChange = getDeleteRootEObjectChange();
        var locksForDeleteRootChange = lockManager.computeLocksFor(deleteRootChange);

        assertEquals(1, locksForDeleteRootChange.size());
        var deleteLock = (ElementLock<EObject>) locksForDeleteRootChange.get(0);
        assertEquals(ROOT, deleteLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, deleteLock.getMode());

        // ReplaceEAttributeEChange
        var setRootEAttributeChange = getRootIntegerReplaceSingleValuedEAttributeChange();
        var locksForReplaceAttributeChange = lockManager.computeLocksFor(setRootEAttributeChange);
        assertEquals(2, locksForReplaceAttributeChange.size());
        var lockOnRoot = (ElementLock<EObject>) locksForReplaceAttributeChange.get(0);
        assertEquals(ROOT, lockOnRoot.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRoot.getMode());
        var lockOnAttribute = (FeatureLock<EObject>) locksForReplaceAttributeChange.get(1);
        assertEquals(ROOT, lockOnAttribute.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnAttribute.getMode());
        assertEquals(ROOT_INTEGER_E_ATTRIBUTE, lockOnAttribute.getFeature());

        // InsertReferenceEChange
        var insertRootEReferenceChange = getIdentifiedInsertReferenceChange();

        var locksForInsertReferenceChange = lockManager.computeLocksFor(insertRootEReferenceChange);
        assertEquals(3, locksForInsertReferenceChange.size());
        var lockOnRootInsert = (ElementLock<EObject>) locksForInsertReferenceChange.get(0);
        assertEquals(ROOT, lockOnRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootInsert.getMode());
        var lockOnNonRootInsert = (ElementLock<EObject>) locksForInsertReferenceChange.get(1);
        assertEquals(NON_ROOT, lockOnNonRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootInsert.getMode());
        var lockOnReferenceInsert = (FeatureLock<EObject>) locksForInsertReferenceChange.get(2);
        assertEquals(ROOT, lockOnReferenceInsert.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceInsert.getMode());
        assertEquals(ROOT_NON_ROOT_E_REFERENCE, lockOnReferenceInsert.getFeature());

        // RemoveReferenceEChange
        var removeRootEReferenceChange = getIdentifiedRemoveEReferenceChange();

        var locksForRemoveReferenceChange = lockManager.computeLocksFor(removeRootEReferenceChange);
        assertEquals(3, locksForRemoveReferenceChange.size());
        var lockOnRootRemove = (ElementLock<EObject>) locksForRemoveReferenceChange.get(0);
        assertEquals(ROOT, lockOnRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootRemove.getMode());
        var lockOnNonRootRemove = (ElementLock<EObject>) locksForRemoveReferenceChange.get(1);
        assertEquals(NON_ROOT, lockOnNonRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootRemove.getMode());
        var lockOnReferenceRemove = (FeatureLock<EObject>) locksForRemoveReferenceChange.get(2);
        assertEquals(ROOT, lockOnReferenceRemove.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceRemove.getMode());
        assertEquals(ROOT_NON_ROOT_E_REFERENCE, lockOnReferenceRemove.getFeature());
    }

    private static InsertEReference<EObject> getIdentifiedInsertReferenceChange() {
        return E_CHANGE_FACTORY.createInsertReferenceChange(
            ROOT,
            ROOT_NON_ROOT_E_REFERENCE,
            NON_ROOT,
            0
        );
    }

    private static ReplaceSingleValuedEAttribute<EObject, Integer> getRootIntegerReplaceSingleValuedEAttributeChange() {
        return E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
            ROOT,
            ROOT_INTEGER_E_ATTRIBUTE,
            0,
            42
        );
    }

    private static CreateEObject<EObject> getCreateRootEObjectChange() {
        return E_CHANGE_FACTORY
            .createCreateEObjectChange(ROOT);
    }

    private static DeleteEObject<EObject> getDeleteRootEObjectChange() {
        return E_CHANGE_FACTORY
            .createDeleteEObjectChange(ROOT);
    }

    private static RemoveEReference<EObject> getIdentifiedRemoveEReferenceChange() {
        return E_CHANGE_FACTORY.createRemoveReferenceChange(
            ROOT,
            ROOT_NON_ROOT_E_REFERENCE,
            NON_ROOT,
            0
        );
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
            getRootIntegerReplaceSingleValuedEAttributeChange(),
            getIdentifiedInsertReferenceChange(),
            getIdentifiedRemoveEReferenceChange(),
            getDeleteRootEObjectChange()
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
        assertLock(locksHeldByTransaction, ROOT, LockMode.EXCLUSIVE, null);
        assertLock(locksHeldByTransaction, NON_ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locksHeldByTransaction, ROOT, LockMode.EXCLUSIVE, ROOT_INTEGER_E_ATTRIBUTE);
        assertLock(locksHeldByTransaction, ROOT, LockMode.EXCLUSIVE, ROOT_NON_ROOT_E_REFERENCE);
        transaction.setToCommited();
    }

    @Test
    void testsForNoLockConflicts() {
        assertLockCompatibility(
            getIdentifiedInsertReferenceChange(),
            getRootIntegerReplaceSingleValuedEAttributeChange()
        );
        assertLockCompatibility(
            getRootIntegerReplaceSingleValuedEAttributeChange(),
            getIdentifiedRemoveEReferenceChange()
        );
        assertLockCompatibility(
            getCreateRootEObjectChange(),
            E_CHANGE_FACTORY.createDeleteEObjectChange(NON_ROOT)
        );
        assertLockCompatibility(
            getDeleteRootEObjectChange(),
            E_CHANGE_FACTORY.createCreateEObjectChange(NON_ROOT)
        );
        assertLockCompatibility(
            getRootIntegerReplaceSingleValuedEAttributeChange(),
            E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
                ROOT,
                ROOT_INTEGER_E_ATTRIBUTE_2,
                0,
                6477
            )
        );
        assertLockCompatibility(
            getIdentifiedInsertReferenceChange(),
            E_CHANGE_FACTORY.createRemoveReferenceChange(
                ROOT,
                ROOT_NON_ROOT_E_REFERENCE_2,
                NON_ROOT,
                0
            )
        );
        assertLockCompatibility(
            getIdentifiedInsertReferenceChange(),
            E_CHANGE_FACTORY.createDeleteEObjectChange(
                AllElementTypesCreators.aet.ValueBased()
            )
        );
    }

    @Test
    void testsForLockConflicts() {
        assertLockConflict(
            getIdentifiedRemoveEReferenceChange(),
            getIdentifiedInsertReferenceChange()
        );
        assertLockConflict(
            getRootIntegerReplaceSingleValuedEAttributeChange(),
            getDeleteRootEObjectChange()
        );
        assertLockConflict(
            getIdentifiedInsertReferenceChange(),
            getIdentifiedRemoveEReferenceChange()
        );
        assertLockConflict(
            getDeleteRootEObjectChange(),
            getIdentifiedRemoveEReferenceChange()
        );
    }

    @Test
    void testSingleTransactionUnlocking() {
        // Create transaction, with two operations
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(getIdentifiedInsertReferenceChange(), getDeleteRootEObjectChange())
            )
        );
        // Create second transaction
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(getRootIntegerReplaceSingleValuedEAttributeChange(), getDeleteRootEObjectChange())
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
        assertLock(locksOfT2, ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locksOfT2, ROOT, LockMode.EXCLUSIVE, ROOT_INTEGER_E_ATTRIBUTE);
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
            new HashSet<>(lockManager.computeLocksFor(change2))
        );
    }

    @Test
    void testLockUpgrade() {
        var transaction1 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(
                    getRootIntegerReplaceSingleValuedEAttributeChange(),
                    getDeleteRootEObjectChange()
                )
            )
        );
        var transaction2 = lockManager.submitTransaction(
            new TransactionalChangeImpl<>(
                List.of(
                    getIdentifiedInsertReferenceChange()
                )
            )
        );

        // T1 -> first operation, SIX lock on ROOT and X lock on integer EAttribute
        transaction1.setToRunning();
        var locks = lockManager.computeNextLocksFor(transaction1);
        transaction1.acceptNextOperation();
        locks.forEach(lock -> lockManager.setLock(lock, transaction1));

        assertEquals(2, locks.size());
        assertLock(locks, ROOT, LockMode.SHARED_INTENSIONAL_EXCLUSIVE, null);
        assertLock(locks, ROOT, LockMode.EXCLUSIVE, ROOT_INTEGER_E_ATTRIBUTE);
        assertEquals(new HashSet<>(locks), lockManager.getLocksHeldBy(transaction1));

        // T1 -> second operation, X locks on ROOT and on EAttribute
        var locksOp2 = lockManager.computeNextLocksFor(transaction1);
        transaction1.acceptNextOperation();
        locksOp2.forEach(lock -> lockManager.setLock(lock, transaction1));
        var locksOfT1 = lockManager.getLocksHeldBy(transaction1);
        assertEquals(2, locksOfT1.size());
        assertLock(locksOfT1, ROOT, LockMode.EXCLUSIVE, null);
        assertLock(locksOfT1, ROOT, LockMode.EXCLUSIVE, ROOT_INTEGER_E_ATTRIBUTE);

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
            getRootIntegerReplaceSingleValuedEAttributeChange(),
            getIdentifiedInsertReferenceChange(),
            getIdentifiedRemoveEReferenceChange(),
            getDeleteRootEObjectChange()
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
