package tools.vitruv.transactions.management.locking;

import lombok.Getter;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.VitruviusChange;

import java.util.ListIterator;

import static com.google.common.base.Preconditions.checkState;

/**
 * A {@link Transaction} describes the execution state of a {@link VitruviusChange}
 * in a transactional context.
 *
 * @param <Element>
 */
public class Transaction<Element> {
    /**
     * The underlying change that is executed.
     */
    @Getter
    private final VitruviusChange<Element> underlyingChange;
    /**
     * Execution status of this transaction.
     */
    @Getter
    private TransactionStatus status;
    /**
     * Pointer to the next single operation.
     */
    private final ListIterator<EChange<Element>> operationIterator;
    /**
     * Operation that we are currently peeking.
     */
    private EChange<Element> peeking = null;

    /**
     * Creates a new transaction.
     *
     * @param underlyingChange - {@link VitruviusChange}
     */
    Transaction(VitruviusChange<Element> underlyingChange) {
        checkState(underlyingChange.containsConcreteChange(), "Transactions can only be created for non-empty VitruviusChanges!");
        this.underlyingChange = underlyingChange;
        status = TransactionStatus.STARTED;
        operationIterator = underlyingChange.getEChanges().listIterator();
    }

    /**
     * Sets a {@code STARTED} transaction to {@code RUNNING}.
     */
    public void setToRunning() {
        checkState(status == TransactionStatus.STARTED
            || status == TransactionStatus.BLOCKED,
            "Can only set a started or blocked transaction to running!");
        status = TransactionStatus.RUNNING;
    }

    public void setToBlocked() {
        checkState(status == TransactionStatus.RUNNING, "Can only set a running transaction to blocked!");
        status = TransactionStatus.BLOCKED;
    }

    /**
     * Sets a {@code RUNNING} transaction to {@code COMMITED}.
     * This only is possible after all operations have been executed.
     */
    public void setToCommited() {
        checkState(status == TransactionStatus.RUNNING, "Can only commit a running transaction!");
        checkState(!hasOperationsToExecute(), "Can only commit a transaction if all operations have been executed!");
        status = TransactionStatus.COMMITED;
    }

    /**
     * Checks if the transaction still has operations to execute, and should therefore still be {@code RUNNING}.
     *
     * @return boolean
     */
    public boolean hasOperationsToExecute() {
        return peeking != null || operationIterator.hasNext();
    }

    /**
     * Moves the iterator one step forward and returns the next {@link EChange}, for which we need to test
     * if it can be executed.
     * Sets the transaction also to peeking for operations.
     *
     * @return {@link EChange}
     * @throws IllegalStateException
     */
    public EChange<Element> peekNextOperation() {
        checkState(hasOperationsToExecute(), "This transaction has no operation to execute!");
        checkState(status == TransactionStatus.RUNNING || status == TransactionStatus.BLOCKED,
            "Cannot peek further operations");

        if (peeking == null) {
            peeking = operationIterator.next();
        }
        return peeking;
    }

    /**
     * Accepts the next operation when in peeking mode, because the tests for it have succeeded.
     */
    public void acceptNextOperation() {
        checkState(status == TransactionStatus.RUNNING, "Can only advance a running transaction!");
        checkState(peeking != null, "We are not peeking for some operation!");
        peeking = null;
    }
}