package tools.vitruv.transactions.management;

import tools.vitruv.change.atomic.EChange;

import java.util.Collection;

/**
 * An observer to scheduling events that a {@link Scheduler} emits:
 *
 * <ol>
 *     <li>Admitting a new transaction,</li>
 *     <li>Setting a transaction to running,</li>
 *     <li>Blocking a transaction,</li>
 *     <li>Executing one step of a transaction,</li>
 *     <li>Commiting a transaction.</li>
 * </ol>
 * @param <E>
 */
public interface SchedulingEventObserver<E> {
    void observeAdmission(Transaction<E> newTransaction);

    void observeRunning(Transaction<E> running);

    void observeExecutionOf(EChange<E> step, Transaction<E> forTransaction);

    void observeBlockOf(Transaction<E> blockedTransaction, Collection<Transaction<E>> blockingTransactions);

    void observeCommit(Transaction<E> commited);
}
