import allElementTypes.Root;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.atomic.uuid.AtomicEChangeUuidResolver;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.impl.TransactionalChangeImpl;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.change.testutils.*;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.framework.vsum.internal.VirtualModelImpl;
import tools.vitruv.transactions.management.locking.C2PLScheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class C2PLSchedulerTest {
    private InternalVirtualModel environment;
    private UuidResolver uuidResolver;
    private AtomicEChangeUuidResolver changeResolver;

    private void setupMultiModelEnvironment(Path testPath) throws IOException {
        environment = new VirtualModelBuilder()
            .withStorageFolder(testPath)
            .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
            .buildAndInitialize();
        var root = CommonCreatorClasses.ROOT;
        var view = getDefaultView(environment).withChangeRecordingTrait();
        modifyView(view, (v) -> {
            v.registerRoot(root, URI.createFileURI(testPath + "/models/root.xml"));
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

    private VitruviusChange<Uuid> getFirstChange() {
        var root = environment.createSelector(
            ViewTypeFactory.createIdentityMappingViewType("Root")
        )
            .getSelectableElements()
            .stream().filter(e -> e instanceof Root)
            .map(e -> (Root) e)
            .findFirst()
            .get();

        var transactionalChange = new TransactionalChangeImpl<Uuid>(
            Stream.of(
                CommonCreatorClasses.getRootIntegerReplaceSingleValuedEAttributeChange(
                    root
                )
            )
                .map(change -> changeResolver.assignIds(change))
                .toList()
        );
        return transactionalChange;
    }

    @Test
    void testCorrectApplicationOfOneChange(@TempDir Path testPath)
        throws IOException {
        setupMultiModelEnvironment(testPath);
        var scheduler = new C2PLScheduler(environment);

        // Apply transaction, check that it has been applied correctly.
        scheduler.admitTransaction(getFirstChange());
        scheduler.nextStep();

        var newRoot = getDefaultView(environment)
            .getRootObjects(Root.class)
            .stream().findFirst().get();

        assertEquals(42, newRoot.getSingleValuedEAttribute());
    }
}
