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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.atomic.eobject.CreateEObject;
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

public class C2PLSchedulerTest {
  private InternalVirtualModel environment;
  private UuidResolver uuidResolver;
  private AtomicEChangeUuidResolver changeResolver;

  private void setupMultiModelEnvironment(Path testPath) {
    environment = new VirtualModelBuilder()
        .withStorageFolder(testPath)
        .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
        .buildAndInitialize();
    var root = CommonCreatorClasses.ROOT;
    var nonRoot = CommonCreatorClasses.NON_ROOT;
    var view = getDefaultView(environment).withChangeRecordingTrait();
    modifyView(view, (v) -> {
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
    var scheduler = new C2PLScheduler(environment);

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
  @Test
  void testMultipleTransactionsAtTheSameTime(@TempDir Path testPath) {
    // Set up environment
    setupMultiModelEnvironment(testPath);

    // Create Changes
    var root = getRoot().get();
    int counter = 8794;
    List<TransactionalChangeImpl<EObject>> changes = new ArrayList<>();
    for (int i = 0; i < counter; i++) {
      changes.add(
          CommonCreatorClasses
              .createTransactionFrom(createNonRootAndInsertionTransaction(counter, root))
      );
    }

    // Submit Transactions
    var scheduler = new C2PLScheduler(environment);
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
  void testCorrectUndoHandling(@TempDir Path testPath) {
    setupMultiModelEnvironment(testPath);

    var scheduler = new C2PLScheduler(environment);
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
    assertTrue(observer.getCommitedTransactions().contains(transaction1));
    assertTrue(observer.getAbortedTransactions().contains(transaction2));

    // Root does not exist
    var root2 = getRoot();
    assertFalse(root2.isPresent());
  }

}
