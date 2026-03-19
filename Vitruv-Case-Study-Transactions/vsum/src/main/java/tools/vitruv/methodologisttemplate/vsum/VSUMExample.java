package tools.vitruv.methodologisttemplate.vsum;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.EobjectFactory;
import tools.vitruv.change.atomic.feature.attribute.AttributeFactory;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.ReferenceFactory;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChangeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.framework.vsum.internal.schedule.OneThreadScheduler;
import tools.vitruv.framework.vsum.internal.schedule.PreconstructedSchedulePropagationStrategy;
import tools.vitruv.framework.vsum.schedule.Scheduler;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelPackage;

/**
 * This class provides an example how to define and use a VSUM.
 */
public class VSUMExample {
  private static Scheduler scheduler;
  private static tools.vitruv.methodologisttemplate.model.model.System model = ModelFactory.eINSTANCE.createSystem();
  private static UuidResolver resolver = UuidResolver.create(new ResourceSetImpl());
  private static Uuid modeluuid;

  public static void main(String[] args) {
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    scheduler = new OneThreadScheduler();
    InternalVirtualModel vsum = createDefaultVirtualModel();
    CommittableView view = getDefaultView(vsum).withChangeDerivingTrait();
    System.out.println("Preregister Root");

    modifyView(view, (CommittableView v) -> {
      v.registerRoot(model, URI.createFileURI(new File("").getAbsolutePath() + "/vsumexample/model.xmi"));
    });
    System.out.println("Registered Root");
    modeluuid = vsum.getUuidResolver().getUuid(vsum.getViewSourceModels().iterator().next().getContents().get(0));
    vsum.registerScheduler(scheduler);
    for (int i = 0; i<10; i++) {
      System.out.println("Added " + i + " to the view");
      int finalI = i;
        Random random = new Random();
        int number = 1;
                //(finalI > 4) ? (random.nextInt(3) + 1) : new int[]{1,1,2,2,3}[finalI];
        switch (number) {
          case 1:
            var comp = ModelFactory.eINSTANCE.createComponent();
            comp.setName("component"+ finalI);
            model.getComponents().add(comp);
            var change = createElement(comp, resolver);
            var namechange = nameChange(change, comp.eClass().getEAttributes().get(ModelPackage.COMPONENT__NAME), "component"+ finalI);
            var insertchange = insertElement(change, model.eClass().getEReferences().get(ModelPackage.SYSTEM__COMPONENTS), model.getComponents().indexOf(comp));
            scheduler.add(VitruviusChangeFactory.getInstance().createTransactionalChange(generateList(change,namechange,insertchange)));
            break;
          case 2:
            var protocol = ModelFactory.eINSTANCE.createProtocol();
            protocol.setName("protocol"+ finalI);
            //system.getProtocols().add(protocol);
            //system.getComponents().get(random.nextInt(system.getComponents().size())).getSupportedProtocols().add(protocol);
            break;
          case 3:
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
  var schedule = scheduler.end();
    System.out.println(schedule.schedule().entrySet());
    var propagated = vsum.propagateSchedule(new PreconstructedSchedulePropagationStrategy(), schedule);
    System.out.println("New View Created" + propagated);
    getSystem(getDefaultView(vsum)).getComponents().forEach(System.out::println);
  }

  private static List<EChange<Uuid>> generateList(EChange<Uuid>... changes) {
      return new ArrayList<>(Arrays.asList(changes));
  }

  private static InsertEReference<Uuid> insertElement(CreateEObject<Uuid> element, org.eclipse.emf.ecore.EReference feature, int index) {
    var change = ReferenceFactory.eINSTANCE.<Uuid>createInsertEReference();
    change.setAffectedElement(modeluuid);
    change.setAffectedFeature(feature);
    change.setNewValue(element.getAffectedElement());
    change.setIndex(index);
    return change;
  }

  private static CreateEObject<Uuid> createElement(EObject element, UuidResolver resolver) {
    var change = EobjectFactory.eINSTANCE.<Uuid>createCreateEObject();
    change.setAffectedElement(resolver.generateUuid(element));
    change.setAffectedEObjectType(element.eClass());
    change.setIdAttributeValue(EcoreUtil.getID(element));
    return change;
  }

  private static EChange<Uuid> nameChange(CreateEObject<Uuid> element, org.eclipse.emf.ecore.EAttribute feature, String newName) {
    var change = AttributeFactory.eINSTANCE.<Uuid, String>createReplaceSingleValuedEAttribute();
    change.setAffectedElement(element.getAffectedElement());
    change.setAffectedFeature(feature);
    change.setNewValue(newName);
    return change;
  }

  private static tools.vitruv.methodologisttemplate.model.model.System getSystem(View v) {
    return v.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();
  }

  private static <T> T get(List<T> list) {
    Random random = new Random();
    return list.get(random.nextInt(list.size()));
  }

  private static InternalVirtualModel createDefaultVirtualModel() {
      return new VirtualModelBuilder()
              .withStorageFolder(Path.of("vsumexample"))
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
