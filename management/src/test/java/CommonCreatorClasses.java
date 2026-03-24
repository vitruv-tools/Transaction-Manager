import allElementTypes.AllElementTypesPackage;
import allElementTypes.NonRoot;
import allElementTypes.Root;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import tools.vitruv.change.atomic.TypeInferringAtomicEChangeFactory;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.testutils.metamodels.AllElementTypesCreators;

public class CommonCreatorClasses {
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

    static InsertEReference<EObject> getIdentifiedInsertReferenceChange() {
        return E_CHANGE_FACTORY.createInsertReferenceChange(
            ROOT,
            ROOT_NON_ROOT_E_REFERENCE,
            NON_ROOT,
            0
        );
    }

    static ReplaceSingleValuedEAttribute<EObject, Integer> getRootIntegerReplaceSingleValuedEAttributeChange(Root root) {
        return E_CHANGE_FACTORY.createReplaceSingleAttributeChange(
            root,
            ROOT_INTEGER_E_ATTRIBUTE,
            0,
            42
        );
    }

    static CreateEObject<EObject> getCreateRootEObjectChange() {
        return E_CHANGE_FACTORY
            .createCreateEObjectChange(ROOT);
    }

    static DeleteEObject<EObject> getDeleteRootEObjectChange(Root root) {
        return E_CHANGE_FACTORY
            .createDeleteEObjectChange(root);
    }

    static RemoveEReference<EObject> getIdentifiedRemoveEReferenceChange() {
        return E_CHANGE_FACTORY.createRemoveReferenceChange(
            ROOT,
            ROOT_NON_ROOT_E_REFERENCE,
            NON_ROOT,
            0
        );
    }
}
