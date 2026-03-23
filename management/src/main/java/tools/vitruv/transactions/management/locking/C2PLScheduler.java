package tools.vitruv.transactions.management.locking;

import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.transactions.management.AbstractScheduler;
import tools.vitruv.transactions.management.Transaction;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.google.common.base.Preconditions.checkState;

/**
 * A scheduler implementing the Conservative Two-Phase Locking (C2PL) algorithm.
 * This scheduler assumes that changes submitted to it are complete, i.e. the submitting
 * threads have executed the changes including CPRs for themselves.
 * Currently, this is "ensured" by checking the absence of consistency preservation rules in
 * the environment.
 */
public class C2PLScheduler extends AbstractScheduler<Uuid> {
    /**
     * Lock manager used to determine if lock requests can be granted.
     */
    private final LockManager<Uuid> lockManager = new LockManager<>();
    /**
     * Waiting queue for admitted transactions.
     */
    private final ConcurrentLinkedQueue<Transaction<Uuid>> transactionQueue
        = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new {@link C2PLScheduler}.
     *
     * @param multiModelEnvironment - {@link VirtualModel}
     */
    public C2PLScheduler(VirtualModel multiModelEnvironment) {
        super(multiModelEnvironment);
    }

    @Override
    protected void applyTransactionOnEnvironment(Transaction<Uuid> transaction) {
        multiModelEnvironment.propagateChange(transaction.getUnderlyingChange());
    }

    /**
     * Admits a new transaction for {@code change} and reports this to
     * all observers.
     *
     * @param change - {@link VitruviusChange}
     */
    @Override
    public void admitTransaction(VitruviusChange<Uuid> change) {
        var newTransaction = lockManager.submitTransaction(change);
        transactionQueue.add(newTransaction);
        observers.forEach(observer -> observer.observeAdmission(newTransaction));
    }

    /**
     * Runs the scheduling algorithm, with the following steps:
     *
     * <ol>
     *     <li>Take the next queued transaction to execute.</li>
     *     <li>Attempt to acquire all locks.</li>
     *     <li>If successful, execute the transaction on {@code multiModelEnvironment}.</li>
     *     <li>Otherwise, block the transaction.</li>
     *     <li>When the lock request succeeds, </li>
     * </ol>
     * @return boolean
     */
    @Override
    public boolean nextStep() {
        if (transactionQueue.isEmpty()) {
            return false;
        }

        // Take next transaction, mark as running
        var transactionToExecute = transactionQueue.poll();
        transactionToExecute.setToRunning();
        observers.forEach(observer -> observer.observeRunning(transactionToExecute));

        // Attempt to preclaim all locks
        Optional<Set<Transaction<Uuid>>> blockingTransactions;
        while (transactionToExecute.hasOperationsToExecute()) {
            blockingTransactions = lockManager.acquireLocksForNextOperation(transactionToExecute);
            if (blockingTransactions.isPresent()) {
                handleBlock(transactionToExecute, blockingTransactions.get());
                return true;
            }
        }

        // Lock request succeeded, execute all operations
        applyTransactionOnEnvironment(transactionToExecute);
        for (var eChange: transactionToExecute.getUnderlyingChange().getEChanges()) {
            observers.forEach(observer -> observer.observeExecutionOf(eChange, transactionToExecute));
        }


        // Release all locks
        releaseAllLocksOf(transactionToExecute);
        // Commit, add all unblocked transactions to the waiting queue.
        var unblockedTransactions = lockManager.commit(transactionToExecute);
        transactionQueue.addAll(unblockedTransactions);
        observers.forEach(observer -> observer.observeCommit(transactionToExecute));
        return true;
    }

    /**
     * Handles a transaction block by releasing all locks that {@code transactionToExecute} holds,
     * marking none its operations to be executable, and informing all observers.
     *
     * @param transactionToExecute - {@link Transaction}
     * @param blockingTransactions - {@link Set}
     */
    private void handleBlock(Transaction<Uuid> transactionToExecute, Set<Transaction<Uuid>> blockingTransactions) {
        releaseAllLocksOf(transactionToExecute);
        // Go back to the start of the transaction, do not execute anything
        while (transactionToExecute.goToPreviousOperation()) {}
        observers.forEach(observer -> observer.observeBlockOf(transactionToExecute, blockingTransactions));
    }

    private void releaseAllLocksOf(Transaction<Uuid> transaction) {
        var locksToRelease = lockManager.getLocksHeldBy(transaction);
        locksToRelease.forEach(lock -> lockManager.unsetLock(lock, transaction));
    }
}
