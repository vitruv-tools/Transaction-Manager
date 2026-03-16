package tools.vitruv.transactions.management.locking;

import lombok.EqualsAndHashCode;
import org.eclipse.emf.ecore.EObject;

/**
 * An {@link ElementLock} is held on a single {@link EObject}, which is the element to be locked.
 */
@EqualsAndHashCode(callSuper = true)
public class ElementLock<Element> extends Lock<Element> {
    /**
     * Creates a new ElementLock on {@code root}.
     *
     * @param root - {@link EObject}
     * @param mode - {@link LockMode}
     */
    public ElementLock(Element root, LockMode mode) {
        super(root, mode);
    }

    @Override
    public Lock<Element> convert(LockMode newMode) {
        return new ElementLock<>(this.root, newMode);
    }
}
