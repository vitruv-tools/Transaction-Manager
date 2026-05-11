import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import allElementTypes.AllElementTypesPackage;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import tools.vitruv.transactions.management.TransactionStatusTracker;
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
    scheduler.runNextStep();

    var newRoot = getRoot();
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

    assertEquals(42, newRoot.getSingleValuedEAttribute());
    scheduler.admitTransaction(transaction2);
    assertTrue(newRoot.getMultiValuedContainmentEReference().isEmpty());
    scheduler.runNextStep();
    assertFalse(newRoot.getMultiValuedContainmentEReference().isEmpty());
  }

  private @NonNull Root getRoot() {
    return environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
        .getSelectableElements()
        .stream().filter(e -> e instanceof Root)
        .map(e -> (Root) e)
        .findFirst()
        .get();
  }

  private @NonNull NonRoot getNonRoot() {
    return environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
        .getSelectableElements()
        .stream().filter(e -> e instanceof NonRoot)
        .map(e -> (NonRoot) e)
        .findFirst()
        .get();
  }

  @Test
  void testCorrectUndoHandling(@TempDir Path testPath) {
    setupMultiModelEnvironment(testPath);

    var scheduler = new C2PLScheduler(environment);
    var root = getRoot();
    var nonRoot = getNonRoot();
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
        CommonCreatorClasses.getDeleteRootEObjectChange(root))
    );

    // Submit transactions
    var transaction1 = scheduler.admitTransaction(vitruvChange1);
    var transaction2 = scheduler.admitTransaction(vitruvChange2);

    // Apply transaction 1
    scheduler.runNextStep();
    // Apply transaction 2, deal with failure.
    // NonRoot -> check nonRoot_Value
    scheduler.runNextStep();

    // T1 succeeds, T2 does not
    assertTrue(observer.getCommitedTransactions().contains(transaction1));
    assertTrue(observer.getAbortedTransactions().contains(transaction2));
  }

}
