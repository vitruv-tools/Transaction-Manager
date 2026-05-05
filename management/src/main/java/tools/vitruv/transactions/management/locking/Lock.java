package tools.vitruv.transactions.management.locking;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.eclipse.emf.ecore.EObject;

/**
 * Describes a hierarchical lock on an {@link EObject} and its contents.
 * The model element on which the lock is set is its {@code root}.
 *
 * <p>Locks occur as keys in {@link LockManager}s under different lock modes.
 * The lock mode is not to be considered for determining the key.
 */
@EqualsAndHashCode()
public abstract class Lock<Element> {
  /**
   * The root of the lock.
   */
  @Getter
  protected final Element root;
  /**
   * The lock mode, how the lock should be treated.
   * Not relevant for computing keys!
   */
  @Getter
  @EqualsAndHashCode.Exclude
  protected LockMode mode;

  /**
   * Creates a new {@link Lock}.
   *
   * @param root - Element
   * @param mode - {@link LockMode}
   */
  protected Lock(Element root, LockMode mode) {
    this.root = root;
    this.mode = mode;
  }

  /**
   * Convert the given lock to have a new, higher lock mode.
   *
   * @param newMode - {@link LockMode}
   * @return new {@link Lock}
   */
  public abstract Lock<Element> convert(LockMode newMode);
}