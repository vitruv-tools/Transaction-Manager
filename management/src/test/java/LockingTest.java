import allElementTypes.AllElementTypesPackage;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.testutils.metamodels.AllElementTypesCreators;
import tools.vitruv.transactions.management.locking.*;

import static org.junit.jupiter.api.Assertions.*;

public class LockingTest {
    /**
     * Tests that a {@link LockManager} determines the required {@link Lock}s for a given
     * {@link EChange}.
     */
    @Test
    void testCorrectComputationOfLocks() {
        Root root = AllElementTypesCreators.aet.Root();
        var eChangeFactory = TypeInferringAtomicEChangeFactory.getInstance();
        var lockManager = new LockManager();

        // CreateEChange
        var createRootChange = eChangeFactory
            .createCreateEObjectChange(root);
        var locksForCreateRootChange = lockManager.computeLocksFor(createRootChange);
        assertEquals(1, locksForCreateRootChange.size());
        var createLock = (ElementLock<Root>) locksForCreateRootChange.get(0);
        assertEquals(root, createLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, createLock.getMode());

        // DeleteEChange
        var deleteRootChange = eChangeFactory
            .createDeleteEObjectChange(root);
        var locksForDeleteRootChange = lockManager.computeLocksFor(deleteRootChange);
        assertEquals(1, locksForDeleteRootChange.size());
        var deleteLock = (ElementLock<Root>) locksForDeleteRootChange.get(0);
        assertEquals(root, deleteLock.getRoot());
        assertEquals(LockMode.EXCLUSIVE, deleteLock.getMode());

        // ReplaceEAttributeEChange
        var rootIntegerEAttribute = AllElementTypesPackage.eINSTANCE
            .getRoot_SingleValuedEAttribute();
        var setRootEAttributeChange = eChangeFactory.createReplaceSingleAttributeChange(
            root,
            rootIntegerEAttribute,
            0,
            42
        );
        var locksForReplaceAttributeChange = lockManager.computeLocksFor(setRootEAttributeChange);
        assertEquals(2, locksForReplaceAttributeChange.size());
        var lockOnRoot = (ElementLock<Root>) locksForReplaceAttributeChange.get(0);
        assertEquals(root, lockOnRoot.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRoot.getMode());
        var lockOnAttribute = (FeatureLock<Root>) locksForReplaceAttributeChange.get(1);
        assertEquals(root, lockOnAttribute.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnAttribute.getMode());
        assertEquals(rootIntegerEAttribute, lockOnAttribute.getFeature());

        var rootNonRootEReference = AllElementTypesPackage.eINSTANCE
            .getRoot_MultiValuedNonContainmentEReference();
        var nonRoot = AllElementTypesCreators.aet.NonRoot();
        // InsertReferenceEChange
        var insertRootEReferenceChange = eChangeFactory.createInsertReferenceChange(
            root,
            rootNonRootEReference,
            nonRoot,
            0
        );

        var locksForInsertReferenceChange = lockManager.computeLocksFor(insertRootEReferenceChange);
        assertEquals(3, locksForInsertReferenceChange.size());
        var lockOnRootInsert = (ElementLock<Root>) locksForInsertReferenceChange.get(0);
        assertEquals(root, lockOnRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootInsert.getMode());
        var lockOnNonRootInsert = (ElementLock<NonRoot>) locksForInsertReferenceChange.get(1);
        assertEquals(nonRoot, lockOnNonRootInsert.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootInsert.getMode());
        var lockOnReferenceInsert = (FeatureLock<Root>) locksForInsertReferenceChange.get(2);
        assertEquals(root, lockOnReferenceInsert.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceInsert.getMode());
        assertEquals(rootNonRootEReference, lockOnReferenceInsert.getFeature());

        // RemoveReferenceEChange
        var removeRootEReferenceChange = eChangeFactory.createRemoveReferenceChange(
            root,
            rootNonRootEReference,
            nonRoot,
            0
        );

        var locksForRemoveReferenceChange = lockManager.computeLocksFor(removeRootEReferenceChange);
        assertEquals(3, locksForRemoveReferenceChange.size());
        var lockOnRootRemove = (ElementLock<Root>) locksForRemoveReferenceChange.get(0);
        assertEquals(root, lockOnRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnRootRemove.getMode());
        var lockOnNonRootRemove = (ElementLock<NonRoot>) locksForRemoveReferenceChange.get(1);
        assertEquals(nonRoot, lockOnNonRootRemove.getRoot());
        assertEquals(LockMode.SHARED_INTENSIONAL_EXCLUSIVE, lockOnNonRootRemove.getMode());
        var lockOnReferenceRemove = (FeatureLock<Root>) locksForRemoveReferenceChange.get(2);
        assertEquals(root, lockOnReferenceRemove.getRoot());
        assertEquals(LockMode.EXCLUSIVE, lockOnReferenceRemove.getMode());
        assertEquals(rootNonRootEReference, lockOnReferenceRemove.getFeature());
    }
}
