
package tools.vitruv.transactions.management.locking;

import lombok.Getter;
import org.eclipse.emf.ecore.EObject;

/**
 * Describes an hierarchical lock on an {@link EObject} and its contents.
 */
public abstract class Lock<Element> {
    /**
     * The root of the lock.
     */
    @Getter
    protected final Element root;
    /**
     * The lock mode, how the lock should be treated.
     */
    @Getter
    protected LockMode mode;

    protected Lock(Element root, LockMode mode) {
        this.root = root;
        this.mode = mode;
    }
}