import allElementTypes.AllElementTypesPackage;
import allElementTypes.Identified;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.transactions.management.locking.TransactionStatus.STARTED;

public class LockingTest {

    public static final Root ROOT = AllElementTypesCreators.aet.Root();
    public static final TypeInferringAtomicEChangeFactory E_CHANGE_FACTORY = TypeInferringAtomicEChangeFactory.getInstance();
    public static final EAttribute ROOT_INTEGER_E_ATTRIBUTE = AllElementTypesPackage.eINSTANCE
        .getRoot_SingleValuedEAttribute();
    public static final EReference ROOT_NON_ROOT_E_REFERENCE = AllElementTypesPackage.eINSTANCE
        .getRoot_MultiValuedNonContainmentEReference();
    public static final NonRoot NON_ROOT = AllElementTypesCreators.aet.NonRoot();

    /**
     * Tests that a {@link LockManager} determines the required {@link Lock}s for given
     * types of {@link EChange}.
     */
    @Test
    void testCorrectComputationOfLocks() {
        var lockManager = new LockManager<EObject>();

        // CreateEChange
        var createRootChange = getCreateEObjectChange();
        var locksForCreateRootChange = lockManager.computeLocksFor(createRootChange);
        assertEquals(1, locksForCreateRootChange.size());
        var createLock = (ElementLock<EObject>) locksForCreateRootChange.get(0);
        assertEquals(ROOT, createLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, createLock.getMode());

        // DeleteEChange
        var deleteRootChange = getDeleteEObjectChange();
        var locksForDeleteRootChange = lockManager.computeLocksFor(deleteRootChange);

        assertEquals(1, locksForDeleteRootChange.size());
        var deleteLock = (ElementLock<EObject>) locksForDeleteRootChange.get(0);
        assertEquals(ROOT, deleteLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, deleteLock.getMode());

        // ReplaceEAttributeEChange
        var setRootEAttributeChange = getRootIntegerReplaceSingleValuedEAttribute();
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
        var insertRootEReferenceChange = getInsertReferenceChange();

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
        var removeRootEReferenceChange = getIdentifiedRemoveEReference();

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

    private static InsertEReference<EObject> getInsertReferenceChange() {
        return E_CHANGE_FACTORY.createInsertReferenceChange(
            ROOT,
            ROOT_NON_ROOT_E_REFERENCE,
            NON_ROOT,
            0
        );
    }

    private static ReplaceSingleValuedEAttribute<EObject, Integer> getRootIntegerReplaceSingleValuedEAttribute() {
        return E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
            ROOT,
            ROOT_INTEGER_E_ATTRIBUTE,
            0,
            42
        );
    }

    private static CreateEObject<EObject> getCreateEObjectChange() {
        return E_CHANGE_FACTORY
            .createCreateEObjectChange(ROOT);
    }

    private static DeleteEObject<EObject> getDeleteEObjectChange() {
        return E_CHANGE_FACTORY
            .createDeleteEObjectChange(ROOT);
    }

    private static RemoveEReference<EObject> getIdentifiedRemoveEReference() {
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
        var lockManager = new LockManager<Object>();
        var emptyVitruviusChange = new TransactionalChangeImpl<>(
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
            getRootIntegerReplaceSingleValuedEAttribute(),
            getInsertReferenceChange(),
            getIdentifiedRemoveEReference(),
            getDeleteEObjectChange()
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
            lockManager.acquireNextLockFor(transaction);
        }
        // All operations have been processed
        assertFalse(transaction.hasOperationsToExecute());
        transaction.setToCommited();
    }
}
