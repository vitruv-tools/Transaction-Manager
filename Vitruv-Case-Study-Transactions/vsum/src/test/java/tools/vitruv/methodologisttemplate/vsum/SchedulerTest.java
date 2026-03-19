package tools.vitruv.methodologisttemplate.vsum;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.EobjectFactory;
import tools.vitruv.change.atomic.feature.attribute.AttributeFactory;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.ReferenceFactory;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.VitruviusChangeFactory;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.framework.vsum.internal.schedule.OneThreadScheduler;
import tools.vitruv.framework.vsum.internal.schedule.PreconstructedSchedulePropagationStrategy;
import tools.vitruv.framework.vsum.schedule.Schedule;
import tools.vitruv.framework.vsum.schedule.Scheduler;
import tools.vitruv.methodologisttemplate.model.model.Component;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.ModelPackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class provides an example how to define and use a VSUM.
 */
public class SchedulerTest {

  public static final int COMPONENT = 1;
  public static final int PROTOCOL = 2;
  public static final int LINK = 3;
  private UuidResolver resolver;
  private InternalVirtualModel vsum;
  private tools.vitruv.methodologisttemplate.model.model.System model;
  private Uuid modeluuid;
  private Path tempDir;
  private int compCount = 10;

  @BeforeAll
  static void setup() {
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());

  }

  @BeforeEach
  void initialize(@TempDir Path tempDir) {
    vsum = createDefaultVirtualModel(tempDir);
    model = ModelFactory.eINSTANCE.createSystem();
    CommittableView view = getDefaultView(vsum).withChangeDerivingTrait();
    modifyView(view, (CommittableView v) -> {
      v.registerRoot(model, URI.createFileURI(tempDir + "/vsumexample/model.xmi"));
    });
    modeluuid = vsum.getUuidResolver().getUuid(vsum.getViewSourceModels().iterator().next().getContents().get(0));
    this.tempDir = tempDir;
    resolver = UuidResolver.create(new ResourceSetImpl());
  }

  private int[] components() {
    return IntStream.generate(() -> COMPONENT).limit(compCount).toArray();
  }

  private int[] componentsAndProtocols() {
    return IntStream.iterate(COMPONENT, n -> n == 1 ? PROTOCOL : COMPONENT).limit(compCount).toArray();
  }

  @Test
  void oneThreadSchedulerDirectPropagation() {
    var scheduler = new OneThreadScheduler();
    vsum.registerScheduler(scheduler);
    var schedule = getSchedule(scheduler, components());
    var start = System.nanoTime();
    var propagated = vsum.propagateSchedule(new PreconstructedSchedulePropagationStrategy(), schedule);
    var end = System.nanoTime()-start;
    System.out.println(end);
    assertEquals(compCount, getSystem(getDefaultView(vsum)).getComponents().size());
    assertEquals(compCount, getRoot(getDefaultView(vsum)).getEntities().size());
  }

  @Test
  void oneThreadSchedulerViewPropagation() {
    var scheduler = new OneThreadScheduler();
    vsum.registerScheduler(scheduler);
    CommittableView view = getDefaultView(vsum).withChangeDerivingTrait();
    List<Component> components = new ArrayList<>();
    for (int i = 0; i < compCount; i++) {
      var comp = ModelFactory.eINSTANCE.createComponent();
      comp.setName("component" + i);
      components.add(comp);
    }
    modifyView(view, (v) -> {
      for (int i = 0; i < compCount; i++) {
        getSystem(v).getComponents().add(components.get(i));
      }
    });
    var schedule = scheduler.end();
    var start = System.nanoTime();
    var propagated = vsum.propagateSchedule(new PreconstructedSchedulePropagationStrategy(), schedule);
    var end = System.nanoTime()-start;
    System.out.println(end);
    assertEquals(compCount, getSystem(getDefaultView(vsum)).getComponents().size());
    assertEquals(compCount, getRoot(getDefaultView(vsum)).getEntities().size());
  }

  /**
   * For now disabled, some IllegalStateException occurs with the UuidResolver
   */
  @Disabled
  @Test
  void oneThreadSchedulerViewPropagationRollingBasis() {
    var scheduler = new OneThreadScheduler();
    vsum.registerScheduler(scheduler);
    CommittableView view = getDefaultView(vsum).withChangeDerivingTrait();
    List<Component> components = new ArrayList<>();
    for (int i = 0; i < compCount; i++) {
      var comp = ModelFactory.eINSTANCE.createComponent();
      comp.setName("component" + i);
      components.add(comp);
    }
    var start = System.nanoTime();
    for (int i = 0; i < compCount; i++) {
      int finalI = i;
      modifyView(view, (v) -> {
          getSystem(v).getComponents().add(components.get(finalI));
        var schedule = scheduler.end();
        var propagated = vsum.propagateSchedule(new PreconstructedSchedulePropagationStrategy(), schedule);
      });
    }
    var end = System.nanoTime()-start;
    System.out.println(end);
    assertEquals(compCount, getSystem(getDefaultView(vsum)).getComponents().size());
    assertEquals(compCount, getRoot(getDefaultView(vsum)).getEntities().size());
  }

  @Test
  void withoutScheduler() {
    var scheduler = new OneThreadScheduler();
    var schedule = getSchedule(scheduler, components());
    var start = System.nanoTime();
      for (VitruviusChange<Uuid> it : schedule.schedule().values().iterator().next().toArray(new VitruviusChange[0])) {
          vsum.propagateChange(it);
      }
      var end = System.nanoTime()-start;
    System.out.println(end);
    assertEquals(compCount, getSystem(getDefaultView(vsum)).getComponents().size());
    assertEquals(compCount, getRoot(getDefaultView(vsum)).getEntities().size());
  }

  @Test
  void twoThreadScheduler() {
    var scheduler = new TwoThreadTestScheduler();
    vsum.registerScheduler(scheduler);
    var schedule = getSchedule(scheduler, componentsAndProtocols());
    var start = System.nanoTime();
    var propagated = vsum.propagateSchedule(new PreconstructedSchedulePropagationStrategy(), schedule);
    var end = System.nanoTime()-start;
    System.out.println(end);
    assertEquals(compCount, getSystem(getDefaultView(vsum)).getComponents().size());
    assertEquals(compCount, getRoot(getDefaultView(vsum)).getEntities().size());
  }

  private Schedule getSchedule(Scheduler scheduler, int[] tasks) {
    for (int i = 0; i<compCount; i++) {
      int number = tasks[i];
      switch (number) {
        case COMPONENT:
          var comp = ModelFactory.eINSTANCE.createComponent();
          comp.setName("component"+ i);
          model.getComponents().add(comp);
          var change = createElement(comp, resolver);
          var namechange = nameChange(change, comp.eClass().getEAttributes().get(ModelPackage.COMPONENT__NAME), "component"+ i);
          var insertchange = insertElement(change, model.eClass().getEReferences().get(ModelPackage.SYSTEM__COMPONENTS), model.getComponents().indexOf(comp));
          scheduler.add(VitruviusChangeFactory.getInstance().createTransactionalChange(generateList(change,namechange,insertchange)));
          break;
        case PROTOCOL:
          var protocol = ModelFactory.eINSTANCE.createProtocol();
          protocol.setName("protocol"+ i);
          model.getProtocols().add(protocol);
          var changeP = createElement(protocol, resolver);
          var namechangeP = nameChange(changeP, protocol.eClass().getEAttributes().get(ModelPackage.PROTOCOL__NAME), "protocol"+ i);
          var insertchangeP = insertElement(changeP, model.eClass().getEReferences().get(ModelPackage.SYSTEM__PROTOCOLS), model.getComponents().indexOf(protocol));
          scheduler.add(VitruviusChangeFactory.getInstance().createTransactionalChange(generateList(changeP,namechangeP,insertchangeP)));
          break;
        case LINK:
          var link = ModelFactory.eINSTANCE.createLink();
          //link.setProtocol(get(system.getProtocols()));
          //link.getComponents().add(get(system.getComponents()));
          //link.getComponents().add(get(system.getComponents()));
          //system.getLinks().add(link);
          break;
        default:
          System.out.println("Unexpected value");
      }
    }
      return scheduler.end();
  }

  private List<EChange<Uuid>> generateList(EChange<Uuid>... changes) {
    return new ArrayList<>(Arrays.asList(changes));
  }

  private InsertEReference<Uuid> insertElement(CreateEObject<Uuid> element, org.eclipse.emf.ecore.EReference feature, int index) {
    var change = ReferenceFactory.eINSTANCE.<Uuid>createInsertEReference();
    change.setAffectedElement(modeluuid);
    change.setAffectedFeature(feature);
    change.setNewValue(element.getAffectedElement());
    change.setIndex(index);
    return change;
  }

  private CreateEObject<Uuid> createElement(EObject element, UuidResolver resolver) {
    var change = EobjectFactory.eINSTANCE.<Uuid>createCreateEObject();
    change.setAffectedElement(resolver.generateUuid(element));
    change.setAffectedEObjectType(element.eClass());
    change.setIdAttributeValue(EcoreUtil.getID(element));
    return change;
  }

  private EChange<Uuid> nameChange(CreateEObject<Uuid> element, org.eclipse.emf.ecore.EAttribute feature, String newName) {
    var change = AttributeFactory.eINSTANCE.<Uuid, String>createReplaceSingleValuedEAttribute();
    change.setAffectedElement(element.getAffectedElement());
    change.setAffectedFeature(feature);
    change.setNewValue(newName);
    return change;
  }

  private tools.vitruv.methodologisttemplate.model.model.System getSystem(View v) {
    return v.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();
  }

  private tools.vitruv.methodologisttemplate.model.model2.Root getRoot(View v) {
    return v.getRootObjects(tools.vitruv.methodologisttemplate.model.model2.Root.class).iterator().next();
  }

  private static InternalVirtualModel createDefaultVirtualModel(Path projectPath) {
    return new VirtualModelBuilder()
            .withStorageFolder(projectPath)
            .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
            .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
            .buildAndInitialize();
  }

  private static View getDefaultView(VirtualModel vsum) {
    var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
    selector.getSelectableElements().forEach(it -> selector.setSelected(it, true));
    return selector.createView();
  }

  private static void modifyView(CommittableView view, Consumer<CommittableView> modificationFunction) {
    modificationFunction.accept(view);
    view.commitChanges();
  }


}
