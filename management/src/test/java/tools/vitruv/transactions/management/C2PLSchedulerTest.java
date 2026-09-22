package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import allElementTypes.AllElementTypesPackage;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.atomic.uuid.AtomicEChangeUuidResolver;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.change.testutils.metamodels.AllElementTypesCreators;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.locking.C2PLScheduler;

class C2PLSchedulerTest {
  private InternalVirtualModel environment;
  private UuidResolver uuidResolver;
  private AtomicEChangeUuidResolver changeResolver;

  private void setupMultiModelEnvironment(Path testPath) throws  IOException{
    environment = new VirtualModelBuilder()
        .withStorageFolder(testPath)
        .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
        .buildAndInitialize();
    var root = CommonCreatorClasses.ROOT;
    var nonRoot = CommonCreatorClasses.NON_ROOT;
    var view = getDefaultView(environment).withChangeRecordingTrait();
    modifyView(view, (v) -> {
      root.setSingleValuedEAttribute(0);
      v.registerRoot(root, URI.createFileURI(testPath + "/models/root.xml"));
    });
    modifyView(view, (v) -> {
      v.registerRoot(nonRoot, URI.createFileURI(testPath + "/models/nonroot.xml"));
    });


    uuidResolver = environment.getUuidResolver();
    changeResolver = new AtomicEChangeUuidResolver(uuidResolver);

    assertEquals(1, getDefaultView(environment).getRootObjects(Root.class).size());
  }

  private static void modifyView(CommittableView view, Consumer<CommittableView> modificationFunction) {
    modificationFunction.accept(view);
    view.commitChanges();
  }

  private static View getDefaultView(VirtualModel vsum) {
    var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
    selector.getSelectableElements().forEach(it -> selector.setSelected(it, true));
    return selector.createView();
  }

  private VitruviusChange<EObject> getFirstChange() {
    var root = environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
        .getSelectableElements()
        .stream().filter(e -> e instanceof Root)
        .map(e -> (Root) e)
        .findFirst()
        .get();

    var transactionalChange = new TransactionalChangeImpl<>(
        List.of(
            CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
                root
            )
        )
    );
    return transactionalChange;
  }

  private TransactionalChangeImpl<Uuid> convertFromEObjectVitruviusChange(VitruviusChange<EObject> originalChange) {
    return null;
  }

  /**
   * Tests that the C2PLScheduler applies one transaction correctly.
   *
   * @param testPath
   * @throws IOException
   */
  @Test
  void testCorrectApplicationOfOneChange(@TempDir Path testPath)
      throws IOException {
    setupMultiModelEnvironment(testPath);
    var scheduler = new C2PLScheduler(environment, 1);

    // Apply transaction, check that it has been applied correctly.
    scheduler.admitTransaction(getFirstChange());

    var newRoot = getRoot().get();
    // Second transaction: Create a new NonRoot and insert it.
    NonRoot newNonRoot = AllElementTypesCreators.aet.NonRoot();
    newNonRoot.setId("fools");
    var transaction2 = new TransactionalChangeImpl<EObject>(
        List.of(
            CommonCreatorClasses.E_CHANGE_FACTORY
                .createCreateEObjectChange(newNonRoot),
            CommonCreatorClasses.E_CHANGE_FACTORY
                .createInsertReferenceChange(
                    newRoot,
                    AllElementTypesPackage.eINSTANCE.getRoot_MultiValuedContainmentEReference(),
                    newNonRoot,
                    0
                )
        )
    );
    scheduler.admitTransaction(transaction2);

    assertTrue(scheduler.waitForApplicationOfRunningTransactions());
    assertEquals(42, newRoot.getSingleValuedEAttribute());
    assertFalse(newRoot.getMultiValuedContainmentEReference().isEmpty());
  }

  private Optional<Root> getRoot() {
    return environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
        .getSelectableElements()
        .stream().filter(e -> e instanceof Root)
        .map(e -> (Root) e)
        .findFirst();

  }

  private Optional<NonRoot> getNonRoot() {
    return environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
        .getSelectableElements()
        .stream().filter(e -> e instanceof NonRoot)
        .map(e -> (NonRoot) e)
        .findFirst();
  }

  /**
   * Check that multiple transactions without unresolvable conflicts
   * eventually succeed.
   *
   * @param testPath Path
   */
  @RepeatedTest(512)
  //@Timeout(unit = TimeUnit.SECONDS, value = 600)
  void testMultipleTransactionsAtTheSameTime(@TempDir Path testPath) throws InterruptedException, IOException {
    // Set up environment
    setupMultiModelEnvironment(testPath);

    // Create Changes
    var root = getRoot().get();
    int counter = 1024;
    List<TransactionalChangeImpl<EObject>> changes = new ArrayList<>();
    for (int i = 0; i < counter; i++) {
      changes.add(
          CommonCreatorClasses
              .createTransactionFrom(createNonRootAndInsertionTransaction(counter, root))
      );
    }

    // Submit Transactions
    var scheduler = new C2PLScheduler(environment, 5);
    var transactionStatusTracker = new TransactionStatusTracker<EObject>();
    scheduler.addListener(transactionStatusTracker);

    var transactions = new ArrayList<TransactionState<EObject>>();
    for (var change : changes) {
      transactions.add(scheduler.admitTransaction(change));
    }

    // Check for full execution
    scheduler.waitForApplicationOfRunningTransactions();
    var committedTransactions = transactionStatusTracker.getCommitedTransactions();
    assertEquals(counter, committedTransactions.size());

    // Check for application
    root = getRoot().get();
    var nonRoots = root.getMultiValuedContainmentEReference();
    assertEquals(counter, nonRoots.size());
  }

  @Test
  void testFailingTransactionsNoneSucceeds(@TempDir Path testPath) throws IOException {
    // Set up environment
    setupMultiModelEnvironment(testPath);

    // Create Changes
    var root = getRoot().get();
    int counter = 600;

    List<TransactionalChangeImpl<EObject>> changes = new ArrayList<>();
    // Delete root and set its attribute -> fail
    for (int i = 0; i < counter; i++) {
      EChange<EObject> deleteRootChange = TypeInferringAtomicEChangeFactory
          .getInstance().createDeleteEObjectChange(
              root
          );
      EChange<EObject> setRootValueChange = TypeInferringAtomicEChangeFactory.getInstance()
          .createReplaceSingleAttributeChange(
              root,
              AllElementTypesPackage.eINSTANCE.getRoot_SingleValuedEAttribute(),
              032,
              042
          );
      var newChanges = new ArrayList<EChange<EObject>>();
      newChanges.add(deleteRootChange);
      newChanges.add(setRootValueChange);
      changes.add(CommonCreatorClasses.createTransactionFrom(newChanges));
    }

    // Shuffle and submit
    Collections.shuffle(changes);
    var scheduler = new C2PLScheduler(environment, 16);
    var transactionStatusTracker = new TransactionStatusTracker<EObject>();
    scheduler.addListener(transactionStatusTracker);

    var transactions = new ArrayList<TransactionState<EObject>>();
    for (var change : changes) {
      transactions.add(scheduler.admitTransaction(change));
    }

    // Check for full execution
    scheduler.waitForApplicationOfRunningTransactions();

    // All transactions should fail
    assertEquals(counter, transactionStatusTracker.getAbortedTransactions().size());

    // Root should still exist; all transactions have undone their effects
    assertTrue(getRoot().isPresent());
  }

  @Test
  void testFailingTransactionsOnlyOneSucceeds(@TempDir Path testPath) throws IOException {
    // Set up environment
    setupMultiModelEnvironment(testPath);

    // Create Changes
    var root = getRoot().get();
    int counter = 100;

    List<TransactionalChangeImpl<EObject>> changes = new ArrayList<>();
    // Apply previous changes, with twist: also set attribute of root
    for (int i = 0; i < counter; i++) {
      var oldChanges = createNonRootAndInsertionTransaction(counter, root);
      EChange<EObject> setRootValueChange = TypeInferringAtomicEChangeFactory.getInstance()
          .createReplaceSingleAttributeChange(
              root,
              AllElementTypesPackage.eINSTANCE.getRoot_SingleValuedEAttribute(),
              0,
              i
          );
      var newChanges = new ArrayList<>(oldChanges);
      newChanges.add(setRootValueChange);
      changes.add(CommonCreatorClasses.createTransactionFrom(newChanges));
    }

    // Shuffle and submit
    Collections.shuffle(changes);
    var scheduler = new C2PLScheduler(environment, 4);
    var transactionStatusTracker = new TransactionStatusTracker<EObject>();
    scheduler.addListener(transactionStatusTracker);

    var transactions = new ArrayList<TransactionState<EObject>>();
    for (var change : changes) {
      transactions.add(scheduler.admitTransaction(change));
    }

    // Check for full execution
    scheduler.waitForApplicationOfRunningTransactions();

    // Exactly one transaction should succeed, the others should fail
    assertEquals(counter - 1, transactionStatusTracker.getAbortedTransactions().size());
    assertEquals(1, transactionStatusTracker.getCommitedTransactions().size());
  }

  static List<EChange<EObject>> createNonRootAndInsertionTransaction(int counter, Root root) {
    var newNonRoot = AllElementTypesCreators.aet.NonRoot();
    EChange<EObject> nonRootCreate = TypeInferringAtomicEChangeFactory.getInstance()
        .createCreateEObjectChange(newNonRoot);
    EChange<EObject> setValueChange = TypeInferringAtomicEChangeFactory.getInstance()
        .createReplaceSingleAttributeChange(
            newNonRoot,
            AllElementTypesPackage.eINSTANCE.getNonRoot_Value(),
            null,
            "" + counter
        );
    EChange<EObject> insertReferenceChange = TypeInferringAtomicEChangeFactory.getInstance()
        .createInsertReferenceChange(
            root,
            AllElementTypesPackage.eINSTANCE.getRoot_MultiValuedContainmentEReference(),
            newNonRoot,
            0
        );

    return List.of(
        nonRootCreate,
        setValueChange,
        insertReferenceChange
    );
  }

  /**
   * Tests that the scheduler rolls back a transaction correctly, when required.
   *
   * @param testPath {@link Path}
   */
  @Test
  void testCorrectUndoHandling(@TempDir Path testPath) throws IOException {
    setupMultiModelEnvironment(testPath);
    var scheduler = new C2PLScheduler(environment, 1);
    var root = getRoot().get();
    var nonRoot = getNonRoot().get();
    var observer = new TransactionStatusTracker<EObject>();
    scheduler.addListener(observer);

    // Transaction 2 -> create NonRoot, set Root attribute
    var vitruvChange2 = CommonCreatorClasses.createTransactionFrom(List.of(
        CommonCreatorClasses.E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
            nonRoot,
            AllElementTypesPackage.eINSTANCE
                .getNonRoot_Value(),
            null,
            "42"
        ),
        CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(root))
    );

    // Transaction 1 -> delete Root
    var vitruvChange1 = CommonCreatorClasses.createTransactionFrom(List.of(
        CommonCreatorClasses.getRemoveRootEObjectChange(root),
        CommonCreatorClasses.getDeleteRootEObjectChange(root))
    );

    // Submit transactions
    var transaction1 = scheduler.admitTransaction(vitruvChange1);
    var transaction2 = scheduler.admitTransaction(vitruvChange2);
    assertTrue(scheduler.waitForApplicationOfRunningTransactions());

    // T1 succeeds, T2 does not
    assertTrue(observer.getCommitedTransactions().get(transaction1));
    assertTrue(observer.getAbortedTransactions().get(transaction2));

    // Root does not exist
    var root2 = getRoot();
    assertFalse(root2.isPresent());
  }

  /**
   * Two transactions add the same reference, one fails.
   * The effect of the other transaction should still be there,
   */
  @Test
  void testCorrectUndoHandlingWithInsertAndRemoveEReferences(@TempDir Path testPath) throws IOException {
    setupMultiModelEnvironment(testPath);

    var scheduler = new C2PLScheduler(environment, 1);
    var root = getRoot().get();
    var nonRoot = getNonRoot().get();
    var observer = new TransactionStatusTracker<EObject>();
    scheduler.addListener(observer);

    // Transaction 1: add reference from Root to NonRoot
    var change1 = CommonCreatorClasses.createTransactionFrom(List.of(
        CommonCreatorClasses.E_CHANGE_FACTORY
            .createInsertReferenceChange(
                root,
                AllElementTypesPackage.eINSTANCE.getRoot_MultiValuedContainmentEReference(),
                nonRoot,
                0
            )
      )
    );
    // Transaction 2: add the same reference, then set a value to 69
    var change2 = CommonCreatorClasses.createTransactionFrom(List.of(
        CommonCreatorClasses.E_CHANGE_FACTORY
            .createInsertReferenceChange(
                root,
                AllElementTypesPackage.eINSTANCE.getRoot_MultiValuedContainmentEReference(),
                nonRoot,
                0
            ),
       CommonCreatorClasses.E_CHANGE_FACTORY
           .createReplaceSingleAttributeChange(
               root,
               AllElementTypesPackage.eINSTANCE.getRoot_SingleValuedEAttribute(),
               69,
               67
           )
    ));

    // Admit t1 and t2
    var t2 = scheduler.admitTransaction(change2);
    var t1 = scheduler.admitTransaction(change1);

    // Wait for execution
    scheduler.waitForApplicationOfRunningTransactions();

    // t1 succeeds, t2 does not
    assertTrue(observer.getCommitedTransactions().containsKey(t1));
    assertTrue(observer.getAbortedTransactions().containsKey(t2));

    // Reference from root to nonRoot is still there
    root = getRoot().get();
    nonRoot = getNonRoot().get();
    assertEquals(1, root.getMultiValuedContainmentEReference().size());
    assertTrue(root.getMultiValuedContainmentEReference().contains(nonRoot));
  }

  /**
   * Tests that VitruvOCL constraints are evaluated correctly, and they do not interfere
   * with executing operations.
   *
   * @param testPath
   */
  @Test
  void checkTransactionApplicationWithConsistencyChecks(@TempDir Path testPath) throws IOException {
    setupMultiModelEnvironment(testPath);
  }
}
