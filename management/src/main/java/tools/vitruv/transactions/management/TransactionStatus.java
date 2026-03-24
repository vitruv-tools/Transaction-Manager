package tools.vitruv.transactions.management;

/**
 * The current processing status of a {@link Transaction}.
 */
public enum TransactionStatus {
    /**
     * Transaction has started, but not yet begun to execute operations.
     */
    STARTED,
    /**
     * Transaction is currently executing operations.
     */
    RUNNING,
    /**
     * Transaction has been blocked due to another transaction.
     */
    BLOCKED,
    /**
     * Transaction has executed all operations and commited.
     */
    COMMITED,
    /**
     * Transaction has encountered an error and needs to be aborted.
     * Some operations may have been executed already.
     */
    ABORTING
}
