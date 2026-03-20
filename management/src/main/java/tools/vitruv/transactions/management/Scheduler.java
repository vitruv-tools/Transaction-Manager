package tools.vitruv.transactions.management;

import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.transactions.management.locking.Lock;

/**
 * A generic scheduler interface.
 *
 * Takes complete {@link VitruviusChange}s and ensures their transactional
 * application on a {@link VirtualModel}, which acts as multi-model environment.
 * Assumes that transactions may hold {@link Lock}s.
 *
 * @param <E> - Type of model elements an environment holds.
 */
public interface Scheduler<E> {
    /**
     * Returns the environment for transactions are scheduled.
     *
     * @return multiModelEnvironment - {@link VirtualModel}
     */
    VirtualModel getMultiModelEnvironment();

    /**
     * Admits {@code change} and creates a new transaction.
     *
     * @param change - {@link VitruviusChange}
     */
    void admitTransaction(VitruviusChange<E> change);

    /**
     * Runs the next step in scheduling transactions
     * (selecting a transaction to run, running the next step, blocking or commiting).
     *
     * @return true if active transactions remain, false otherwise.
     */
    boolean nextStep();

    /**
     * Adds {@code observer} to observe scheduling events.
     *
     * @param observer - {@link SchedulingEventObserver}
     */
    void addListener(SchedulingEventObserver<E> observer);

    /**
     * Removes {@code observer} as scheduling event observer.
     *
     * @param observer - {@link SchedulingEventObserver}
     */
    void removeListener(SchedulingEventObserver<E> observer);
}
