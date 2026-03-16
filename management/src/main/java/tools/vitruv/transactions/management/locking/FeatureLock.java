package tools.vitruv.transactions.management.locking;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

/**
 * A {@link Lock} that is not held on an {@link EObject}, but an attribute or reference,
 * in other words, an {@link EStructuralFeature}.
 * FeatureLocks are always exclusive locks.
 */
@EqualsAndHashCode(callSuper = true)
public class FeatureLock<Element> extends Lock<Element> {
    /**
     * The feature of {@code root} that needs to be locked.
     */
    @Getter
    protected final EStructuralFeature feature;

    /**
     * Creates a new {@link FeatureLock}.
     *
     * @param root - {@link EObject}
     * @param feature - {@link EStructuralFeature}
     */
    public FeatureLock(Element root, EStructuralFeature feature) {
        super(root, LockMode.EXCLUSIVE);
        this.feature = feature;
    }

    @Override
    public Lock<Element> convert(LockMode newMode) {
        return new FeatureLock<>(this.root, this.feature);
    }
}
