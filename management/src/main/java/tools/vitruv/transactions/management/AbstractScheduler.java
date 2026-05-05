package tools.vitruv.transactions.management;

import java.util.LinkedList;
import java.util.List;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * An abstract scheduler that mostly holds required data.
 *
 * @param <E> Type of model elements that the MME manages.
 */
public abstract class AbstractScheduler<E> implements Scheduler<E> {
  /**
   * The multi-model environment where transactions are applied on.
   */
  protected final InternalVirtualModel multiModelEnvironment;

  @Override
  public VirtualModel getMultiModelEnvironment() {
    return multiModelEnvironment;
  }

  /**
   * Observers for scheduling events.
   */
  protected final List<SchedulingEventObserver<E>> observers
      = new LinkedList<>();

  /**
   * Creates a new {@link AbstractScheduler}.
   *
   * @param multiModelEnvironment - {@link VirtualModel}
   */
  protected AbstractScheduler(InternalVirtualModel multiModelEnvironment) {
    this.multiModelEnvironment = multiModelEnvironment;
  }

  /**
   * Applies {@code transaction} on {@link AbstractScheduler#multiModelEnvironment}.
   *
   * <p>Concrete implementations of this method can make further restrictions on {@code transaction}
   * and {@link AbstractScheduler#multiModelEnvironment}.
   *
   * @param transaction - {@link Transaction}
   */
  protected abstract void applyTransactionOnEnvironment(Transaction<E> transaction);

  @Override
  public void addListener(SchedulingEventObserver<E> observer) {
    observers.add(observer);
  }

  @Override
  public void removeListener(SchedulingEventObserver<E> observer) {
    observers.remove(observer);
  }
}
