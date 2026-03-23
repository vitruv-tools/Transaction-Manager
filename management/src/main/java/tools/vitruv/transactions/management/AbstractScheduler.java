package tools.vitruv.transactions.management;

import lombok.Getter;
import tools.vitruv.framework.vsum.VirtualModel;

import java.util.LinkedList;
import java.util.List;

/**
 * An abstract scheduler that mostly holds required data.
 * @param <E>
 */
public abstract class AbstractScheduler<E> implements Scheduler<E>{
    @Getter
    protected final VirtualModel multiModelEnvironment;

    protected final List<SchedulingEventObserver<E>> observers
        = new LinkedList<>();

    /**
     * Creates a new {@link AbstractScheduler}.
     *
     * @param multiModelEnvironment - {@link VirtualModel}
     */
    protected AbstractScheduler(VirtualModel multiModelEnvironment) {
        this.multiModelEnvironment = multiModelEnvironment;
    }

    /**
     * Applies {@code transaction} on {@link AbstractScheduler#multiModelEnvironment}.
     * <p>
     * Concrete implementations of this method can make further restrictions on {@code transaction}
     * and {@link AbstractScheduler#multiModelEnvironment}.
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
