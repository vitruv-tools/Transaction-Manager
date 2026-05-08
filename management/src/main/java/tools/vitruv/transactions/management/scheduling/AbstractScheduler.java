package tools.vitruv.transactions.management.scheduling;

import java.util.LinkedList;
import java.util.List;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.transactions.management.TransactionState;

/**
 * An abstract scheduler that mostly holds required data.
 *
 * @param <E> Type of model elements that the MME manages.
 */
public abstract class AbstractScheduler<E, T extends TransactionExecutorThread<E>>
    implements Scheduler<E, T> {
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
   * @param transaction - {@link TransactionState}
   * @throws IllegalArgumentException
   *      Thrown if an {@link EChange} in {@code transaction} cannot be resolved.
   * @throws IllegalStateException
   *      Thrown if an {@link EChange} in {@code transaction} cannot be executed.
   */
  protected abstract void applyTransactionOnEnvironment(TransactionState<E> transaction);

  @Override
  public void addListener(SchedulingEventObserver<E> observer) {
    observers.add(observer);
  }

  @Override
  public void removeListener(SchedulingEventObserver<E> observer) {
    observers.remove(observer);
  }
}
