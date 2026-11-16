package tools.vitruv.transactions.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import allElementTypes.AllElementTypesFactory;
import allElementTypes.AllElementTypesPackage;
import allElementTypes.Root;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.MetamodelDescriptor;
import tools.vitruv.change.composite.description.TransactionalChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.VitruviusChangeResolver;
import tools.vitruv.change.composite.description.VitruviusChangeResolverFactory;
import tools.vitruv.change.composite.recording.ChangeRecorder;
import tools.vitruv.change.correspondence.Correspondence;
import tools.vitruv.change.correspondence.view.EditableCorrespondenceModelView;
import tools.vitruv.change.interaction.UserInteractionFactory;
import tools.vitruv.change.interaction.impl.PredefinedInteractionResultProviderImpl;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.change.propagation.ChangePropagationSpecificationRepository;
import tools.vitruv.change.propagation.impl.AbstractChangePropagationSpecification;
import tools.vitruv.change.propagation.impl.ChangePropagator;
import tools.vitruv.change.propagation.impl.DefaultChangeRecordingModelRepository;
import tools.vitruv.change.utils.ResourceAccess;
import tools.vitruv.transactions.management.locking.HierarchicalLockComputer;
import tools.vitruv.transactions.management.locking.LockKeyProvider.KeyRequest;
import tools.vitruv.transactions.management.locking.LockManager;
import tools.vitruv.transactions.management.locking.LockMode;
import tools.vitruv.transactions.management.locking.LockingStepGuard;

/**
 * Strict 2PL through the {@code ChangePropagator}'s step guard: two propagations on two threads
 * run into a deadlock between their user changes (instance locks) and the type-level write locks
 * of their propagation steps. The lock manager aborts one, the propagator reverts and retries it.
 */
class LockingStepGuardTest {
  private static final EAttribute VALUE =
      AllElementTypesPackage.Literals.ROOT__SINGLE_VALUED_EATTRIBUTE;
  private static final EAttribute PRIMITIVE =
      AllElementTypesPackage.Literals.ROOT__SINGLE_VALUED_PRIMITIVE_TYPE_EATTRIBUTE;
  private static final long TIMEOUT_SECONDS = 30;

  @TempDir Path projectPath;

  private DefaultChangeRecordingModelRepository repository;
  private ResourceSet viewResourceSet;
  private ChangeRecorder viewRecorder;
  private VitruviusChangeResolver<Uuid> viewResolver;
  private ExecutorService executor;

  @BeforeAll
  static void registerResourceFactory() {
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
        .putIfAbsent("xmi", new XMIResourceFactoryImpl());
    AllElementTypesPackage.eINSTANCE.eClass();
  }

  @BeforeEach
  void setUp() {
    // the UUID resolver is not thread-safe yet (finding C4 of the thread-safety analysis); until it
    // is, the operations that use it are serialised here (the propagation steps themselves are
    // guarded by the lock manager)
    repository = new DefaultChangeRecordingModelRepository(null, projectPath) {
      @Override
      public synchronized VitruviusChange<EObject> applyChange(VitruviusChange<Uuid> change) {
        return super.applyChange(change);
      }

      @Override
      public synchronized Iterable<TransactionalChange<EObject>> recordChanges(
          Runnable changeApplicator) {
        return super.recordChanges(changeApplicator);
      }

      @Override
      public synchronized void revertChanges(List<TransactionalChange<EObject>> appliedChanges) {
        super.revertChanges(appliedChanges);
      }
    };
    viewResourceSet = new ResourceSetImpl();
    viewRecorder = new ChangeRecorder(viewResourceSet);
    viewRecorder.addToRecording(viewResourceSet);
    viewResolver = VitruviusChangeResolverFactory.forUuids(UuidResolver.create(viewResourceSet));
    executor = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void tearDown() throws Exception {
    executor.shutdownNow();
    viewRecorder.close();
    repository.close();
  }

  /**
   * Mirrors the two attributes between the roots: a new {@code VALUE} of one root is copied to
   * {@code PRIMITIVE} of the other one and vice versa (only if different, so it terminates).
   */
  private class MirrorSpecification extends AbstractChangePropagationSpecification {
    MirrorSpecification() {
      super(MetamodelDescriptor.with(AllElementTypesPackage.eNS_URI),
          MetamodelDescriptor.with(AllElementTypesPackage.eNS_URI));
    }

    @Override
    public boolean doesHandleChange(EChange<EObject> change,
        EditableCorrespondenceModelView<Correspondence> correspondenceModel) {
      return true;
    }

    @Override
    public void propagateChange(EChange<EObject> change,
        EditableCorrespondenceModelView<Correspondence> correspondenceModel,
        ResourceAccess resourceAccess) {
      if (!(change instanceof ReplaceSingleValuedEAttribute<EObject, ?> replace)
          || !(replace.getNewValue() instanceof Integer value)
          || !(replace.getAffectedElement() instanceof Root changed)) {
        return;
      }
      Root other = otherRoot(changed);
      if (other == null) {
        return;
      }
      if (replace.getAffectedFeature() == VALUE && other.getSingleValuedPrimitiveTypeEAttribute()
          != value) {
        other.setSingleValuedPrimitiveTypeEAttribute(value);
      } else if (replace.getAffectedFeature() == PRIMITIVE
          && !Integer.valueOf(value).equals(other.getSingleValuedEAttribute())) {
        other.setSingleValuedEAttribute(value);
      }
    }
  }

  private Root otherRoot(Root root) {
    for (Resource resource : repository.getModelResources()) {
      if (!resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Root r
          && r != root) {
        return r;
      }
    }
    return null;
  }

  /** What the mirror specification may write when it propagates {@code change} (its footprint). */
  private static List<KeyRequest> mirrorFootprint(EChange<EObject> change) {
    if (change instanceof ReplaceSingleValuedEAttribute<EObject, ?> replace) {
      EAttribute written = replace.getAffectedFeature() == VALUE ? PRIMITIVE
          : replace.getAffectedFeature() == PRIMITIVE ? VALUE : null;
      if (written != null) {
        return List.of(
            new KeyRequest(HierarchicalLockComputer.featureKey(written), LockMode.EXCLUSIVE),
            new KeyRequest("mm:" + AllElementTypesPackage.eNS_URI,
                LockMode.SHARED_INTENSIONAL_EXCLUSIVE));
      }
    }
    return List.of();
  }

  private VitruviusChange<Uuid> recordInView(Runnable modification) {
    viewRecorder.beginRecording();
    modification.run();
    return viewResolver.assignIds(viewRecorder.endRecording());
  }

  private Root createRootInView(String name) {
    Root root = AllElementTypesFactory.eINSTANCE.createRoot();
    root.setId(name);
    viewResourceSet.createResource(
        URI.createFileURI(projectPath.resolve("model/" + name + ".xmi").toString()))
        .getContents().add(root);
    return root;
  }

  @RepeatedTest(20)
  void deadlockedPropagationIsRevertedAndRetried() throws Exception {
    var specification = new MirrorSpecification();
    var propagator = new ChangePropagator(repository,
        new ChangePropagationSpecificationRepository(List.of(specification),
            Map.of((ChangePropagationSpecification) specification, 0)),
        UserInteractionFactory.instance.createUserInteractor(
            new PredefinedInteractionResultProviderImpl(null)));
    List<Root> viewRoots = new ArrayList<>();
    propagator.propagateChange(recordInView(() -> {
      viewRoots.add(createRootInView("a"));
      viewRoots.add(createRootInView("b"));
    }), List.of());

    // both threads apply their user change (holding its instance locks) before either one asks
    // for the locks of its first propagation step: a deadlock on the type-level feature locks
    CyclicBarrier bothApplied = new CyclicBarrier(2);
    AtomicInteger aborts = new AtomicInteger();
    List<String> stepsWaitedFor = Collections.synchronizedList(new ArrayList<>());
    var lockManager = new LockManager<EObject>();
    ThreadLocal<Boolean> firstStep = ThreadLocal.withInitial(() -> true);
    var guard = new LockingStepGuard(lockManager, repository.getUuidResolver(),
        (change, spec) -> {
          if (firstStep.get()) {
            firstStep.set(false);
            try {
              bothApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
              stepsWaitedFor.add("barrier: " + e);
            }
          }
          return mirrorFootprint(change);
        }) {
      @Override
      public void endPropagation(VitruviusChange<Uuid> change, boolean committed) {
        if (!committed) {
          aborts.incrementAndGet();
        }
        super.endPropagation(change, committed);
      }
    };
    propagator.setStepGuard(guard);

    var setValueOfA = recordInView(() -> viewRoots.get(0).setSingleValuedEAttribute(5));
    var setPrimitiveOfB =
        recordInView(() -> viewRoots.get(1).setSingleValuedPrimitiveTypeEAttribute(9));
    Future<?> first = executor.submit(() -> propagator.propagateChange(setValueOfA, List.of()));
    Future<?> second =
        executor.submit(() -> propagator.propagateChange(setPrimitiveOfB, List.of()));
    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertEquals(List.of(), stepsWaitedFor);
    assertEquals(1, aborts.get(), "exactly one propagation is the deadlock victim");
    Root a = (Root) repository.getModelResources().stream()
        .filter(r -> r.getURI().lastSegment().equals("a.xmi")).findFirst().orElseThrow()
        .getContents().get(0);
    Root b = otherRoot(a);
    // serial outcome: whichever ran second determines both mirrored values
    assertTrue(Integer.valueOf(5).equals(a.getSingleValuedEAttribute())
        && b.getSingleValuedPrimitiveTypeEAttribute() == 5
        || Integer.valueOf(9).equals(a.getSingleValuedEAttribute())
        && b.getSingleValuedPrimitiveTypeEAttribute() == 9,
        "not a serial outcome: a.value=" + a.getSingleValuedEAttribute() + ", b.primitive="
            + b.getSingleValuedPrimitiveTypeEAttribute());
  }

  /** Propagations on disjoint footprints run concurrently without aborts. */
  @Test
  void disjointPropagationsDoNotBlockEachOther() throws Exception {
    var propagator = new ChangePropagator(repository,
        new ChangePropagationSpecificationRepository(List.of(), Map.of()),
        UserInteractionFactory.instance.createUserInteractor(
            new PredefinedInteractionResultProviderImpl(null)));
    List<Root> viewRoots = new ArrayList<>();
    propagator.propagateChange(recordInView(() -> {
      viewRoots.add(createRootInView("a"));
      viewRoots.add(createRootInView("b"));
    }), List.of());
    var lockManager = new LockManager<EObject>();
    CyclicBarrier bothInside = new CyclicBarrier(2);
    var guard = new LockingStepGuard(lockManager, repository.getUuidResolver(),
        (change, spec) -> List.of()) {
      @Override
      public void beforeApply(TransactionalChange<Uuid> change) {
        super.beforeApply(change);
        try {
          // both hold their locks at the same time
          bothInside.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new IllegalStateException("the other propagation was blocked", e);
        }
      }
    };
    propagator.setStepGuard(guard);
    var changeA = recordInView(() -> viewRoots.get(0).setSingleValuedEAttribute(1));
    var changeB = recordInView(() -> viewRoots.get(1).setSingleValuedEAttribute(2));
    Future<?> first = executor.submit(() -> propagator.propagateChange(changeA, List.of()));
    Future<?> second = executor.submit(() -> propagator.propagateChange(changeB, List.of()));
    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
}
