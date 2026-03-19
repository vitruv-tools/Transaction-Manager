package tools.vitruv.transactions.management.locking;

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

    protected AbstractScheduler(VirtualModel multiModelEnvironment) {
        this.multiModelEnvironment = multiModelEnvironment;
    }

    @Override
    public void addListener(SchedulingEventObserver<E> observer) {
        observers.add(observer);
    }

    @Override
    public void removeListener(SchedulingEventObserver<E> observer) {
        observers.remove(observer);
    }
}
