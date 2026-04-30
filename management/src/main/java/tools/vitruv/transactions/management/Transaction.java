package tools.vitruv.transactions.management;

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
    private final VitruviusChange<Element> underlyingChange;
    /**
     * Execution status of this transaction.
     */
    @Getter
    private TransactionStatus status;

    /**
     * Pointer to the next single operation whose executability we want to test,
     * i.e. through locking.
     */
    private final ListIterator<EChange<Element>> operationToTestPointer;
    /**
     * Operation that we are currently peeking for executability.
     */
    private EChange<Element> peeking = null;
    /**
     * Current index of the {@link Transaction#operationToTestPointer}.
     */
    @Getter
    private int operationTestIndex = -1;

    /**
     * Pointer to the next operations that we want to execute.
     */
    private final ListIterator<EChange<Element>> operationToExecutePointer;
    /**
     * Current index of the {@link Transaction#operationToExecutePointer}.
     */
    @Getter
    private int operationExecuteIndex = -1;

    /**
     * Creates a new transaction.
     *
     * @param underlyingChange - {@link VitruviusChange}
     */
    public Transaction(VitruviusChange<Element> underlyingChange) {
        checkState(underlyingChange.containsConcreteChange(), "Transactions can only be created for non-empty VitruviusChanges!");
        this.underlyingChange = underlyingChange;
        status = TransactionStatus.STARTED;
        operationToTestPointer = underlyingChange.getEChanges().listIterator();
        operationToExecutePointer = underlyingChange.getEChanges().listIterator();
    }

    /**
     * Sets a {@code STARTED} transaction to {@code RUNNING}.
     */
    public void setToRunning() {
        checkState(status == TransactionStatus.STARTED || status == TransactionStatus.BLOCKED,
            "Can only set a started or blocked transaction to running, but this transaction is %s!",
            status);
        status = TransactionStatus.RUNNING;
    }

    /**
     * Sets a {@code RUNNING} transaction to {@code BLOCKED}.
     */
    public void setToBlocked() {
        checkState(status == TransactionStatus.RUNNING, "Can only set a running transaction to blocked!");
        status = TransactionStatus.BLOCKED;
    }

    /**
     * Sets a {@code RUNNING} or {@code BLOCKING} transaction to {@code ABORTING}.
     */
    public void setToAborting() {
        checkState(status == TransactionStatus.RUNNING || status == TransactionStatus.BLOCKED,
            "Can only abort a running or blocked transaction!");
        status = TransactionStatus.ABORTING;
    }

    public void setToAborted() {
        checkState(status == TransactionStatus.ABORTING, "Can only abort from ABORTING!");
        checkState(!hasOperationsToInvert(), "Some operations still need to be inverted!");
        status = TransactionStatus.ABORTED;
    }

    /**
     * Sets a {@code RUNNING} transaction to {@code COMMITED}.
     * This only is possible after all operations have been executed.
     */
    public void setToCommited() {
        checkState(status == TransactionStatus.RUNNING, "Can only commit a running transaction!");
        checkState(!wantsToAcquireLocks(), "Can only commit a transaction if all operations have been executed!");
        status = TransactionStatus.COMMITED;
    }

    /**
     * Checks if the transaction still has operations to acquire locks for,
     * and should therefore still be {@code RUNNING}.
     *
     * @return boolean
     */
    public boolean wantsToAcquireLocks() {
        return peeking != null || operationToTestPointer.hasNext();
    }

    /**
     * Moves the iterator one step forward and returns the next {@link EChange}, for which we need to test
     * if it can be executed.
     * Sets the transaction also to peeking for operations.
     *
     * @return {@link EChange}
     * @throws IllegalStateException
     */
    public EChange<Element> peekNextOperationForExecutionChecking() {
        checkState(wantsToAcquireLocks(), "This transaction has no operation to acquire locks for!");
        checkState(status == TransactionStatus.RUNNING || status == TransactionStatus.BLOCKED,
            "Cannot peek further operations");

        if (peeking == null) {
            peeking = operationToTestPointer.next();
        }
        return peeking;
    }

    /**
     * Accepts the next operation when in peeking mode, because the tests for it have succeeded.
     */
    public void markNextOperationAsExecutable() {
        checkState(status == TransactionStatus.RUNNING, "Can only advance a running transaction!");
        checkState(peeking != null, "We are not peeking for some operation!");
        peeking = null;
        operationTestIndex++;
    }

    /**
     * Checks if there are operations to execute, i.e. that have not been executed yet, but for which
     * the executability check has succeeded.
     *
     * @return boolean
     */
    public boolean hasExecutableOperations() {
        return status == TransactionStatus.RUNNING && operationExecuteIndex < operationTestIndex;
    }

    /**
     * Checks if there are operations to invert when aborting, i.e. that have been executed already.
     *
     * @return boolean
     */
    public boolean hasOperationsToInvert() {
        return status == TransactionStatus.ABORTING && operationExecuteIndex >= 0;
    }

    /**
     * Returns the next operation/{@link EChange} that can be executed by a transaction.
     * Conditions:
     * <ul>
     *     <li>Only operations can be executed for which the execution check has passed
     *     (cf. {@link Transaction#hasExecutableOperations()})</li>
     * </ul>
     * @return {@link EChange}
     */
    public EChange<Element> getNextOperationForExecution() {
        checkState(status == TransactionStatus.RUNNING,
            "Can only execute operations of a running transaction!");
        checkState(hasExecutableOperations(),
            "Cannot execute the next operation because its tests have not succeeded yet!");
        var operationToExecute = operationToExecutePointer.next();
        operationExecuteIndex++;
        return operationToExecute;
    }

    public EChange<Element> getNextInverseOpration() {
        checkState(status == TransactionStatus.ABORTING,
            "Can only invert operations when aborting a transaction!");
        checkState(hasOperationsToInvert(),
            "All operations have been inverted already!");
        var operationToInvert = operationToExecutePointer.previous();
        operationExecuteIndex--;
        return InverseEChangeComputer.computeInverseOf(operationToInvert);
    }

    /**
     * Goes back to the previous operation because the current operation cannot be executed right now.
     * Assumes that any locks for the previous operation have been released.
     *
     * @return boolean - true if there is another operation that has been accepted,
     *  false if there is none other.
     */
    public boolean goToPreviousOperationForExecutionCheck() {
        checkState(status == TransactionStatus.BLOCKED, "Can not go back to previous operation for non-blocking transactions!");
        var hasPrevious = operationToTestPointer.hasPrevious();
        if (hasPrevious) {
            peeking = operationToTestPointer.previous();
            operationTestIndex--;
        }
        return hasPrevious;
    }
}