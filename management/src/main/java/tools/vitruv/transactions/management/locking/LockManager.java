package tools.vitruv.transactions.management.locking;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.transactions.management.Transaction;
import tools.vitruv.transactions.management.TransactionStatus;


/**
 * A {@link LockManager} manages multiple locks for multiple transactions.
 * Transactions wrap {@link VitruviusChange}s that operate on some type of {@code Element}.
 *
 * @param <E> - The Element type.
 */
public class LockManager<E> {
  /**
   * Manages lock information.
   */
  private final Map<Lock<E>, LockData<E>> lockData = new HashMap<>();
  /**
   * Manages transaction information.
   */
  private final Map<Transaction<E>, TransactionLockingData<E>> transactionData = new HashMap<>();

  /**
   * Submits a new {@link VitruviusChange} to run as transaction.
   *
   * @param change - {@link VitruviusChange}
   */
  public synchronized Transaction<E> submitTransaction(VitruviusChange<E> change) {
    var newTransaction = new Transaction<>(change);
    transactionData.put(newTransaction, new TransactionLockingData<>());
    return newTransaction;
  }

  /**
   * Returns all locks that {@code transaction} holds.
   *
   * @param transaction - {@link Transaction}
   * @return {@link Set}
   */
  public synchronized Set<Lock<E>> getLocksHeldBy(Transaction<E> transaction) {
    checkArgument(transactionData.containsKey(transaction),
        "The transaction is not currently active!");
    return Set.copyOf(transactionData.get(transaction).getHeldLocks());
  }

  /**
   * Attempts to acquire all locks that the next {@link EChange} of {@code transaction} requires.
   * If the lock request fails, returns a mapping of:
   * <ol>
   *  <li>the lock that cannot be acquired,</li>
   *  <li>the other transactions blocking {@code transaction}.</li>
   * </ol>
   *
   * @param transaction - {@link Transaction}
   * @return {@link Optional}
   */
  public synchronized Optional<Set<Transaction<E>>> acquireLocksForNextOperation(
      Transaction<E> transaction) {
    var data = transactionData.get(transaction);
    checkArgument(data != null, "Transactions is not being processed!");
    checkArgument(!data.isUnlocking(),
        "Transaction has started to unlock; it must not acquire further locks!");
    checkArgument(transaction.getStatus() == TransactionStatus.RUNNING,
        "Cannot acquire locks if the transaction is not running!");
    // Peek operation
    var operation = transaction.peekNextOperationForExecutionChecking();
    // Compute locks
    var locksToAcquire = LockComputer.computeLocksFor(operation);

    // Identify all blocking transactions.
    for (var lock : locksToAcquire) {
      var blockingTransactions = testLock(lock, transaction);
      if (blockingTransactions.isPresent()) {
        // Mark transaction as blocked
        data.blockOn(blockingTransactions.get());
        transaction.setToBlocked();
        return blockingTransactions;
      }
    }
    // Else, transaction succeeds
    for (var lock : locksToAcquire) {
      setLock(lock, transaction);
    }
    transaction.markNextOperationAsExecutable();
    return Optional.empty();
  }

  /**
   * Acquires {@code lockToAcquire} for {@code transaction}.
   *
   * @param lockToAcquire - {@link Lock}
   * @param transaction -  {@link Transaction}
   * @return {@link Optional}
   *      The Optional type holds another transaction that already has the lock,
   *      and prevents its acquisition.
   */
  public synchronized Optional<Set<Transaction<E>>> testLock(Lock<E> lockToAcquire,
                                                             Transaction<E> transaction) {
    var data = lockData.get(lockToAcquire);
    // If no other transaction holds the lock, the request succeeds.
    if (data == null) {
      return Optional.empty();
    }

    // If only the current transaction holds the lock, the request also succeeds.
    // Convert the lock, if required.
    var holdingTransactions = data.getHolders();
    if (holdingTransactions.size() == 1 && holdingTransactions.contains(transaction)) {
      return Optional.empty();
    }

    // If more than one transaction holds the lock in SIX mode, and the current transaction also
    // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
    // If more than one transaction holds it, this is an indicator thereof.
    if (lockToAcquire.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE
        && data.getMode() == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
      return Optional.empty();
    }

    // Return all locking transactions.
    return Optional.of(holdingTransactions);
  }


  /**
   * Actually acquires a lock for {@code transaction} and updates information in the lock manager.
   *
   * @param acquiredLock - {@link Lock}
   * @param transaction -  {@link Transaction}
   */
  public synchronized void setLock(Lock<E> acquiredLock, Transaction<E> transaction) {
    checkArgument(transactionData.containsKey(transaction),
        "This transaction may not acquire locks!");
    var data = lockData.get(acquiredLock);
    // If no other transaction holds the lock, the request succeeds.
    if (data == null) {
      lockData.put(acquiredLock, new LockData<>(acquiredLock, transaction));
    } else {
      var holders = data.getHolders();
      // If only the current transaction holds the lock, the request also succeeds.
      // Convert the lock, if required.
      if (holders.size() == 1 && holders.contains(transaction)) {
        var currentLockMode = data.getMode();
        var newLockMode = LockMode.highestLockMode(currentLockMode, acquiredLock.mode);
        var upgradedLock = acquiredLock.convert(newLockMode);

        lockData.put(upgradedLock, new LockData<>(upgradedLock, transaction));
        return;
      } else {
        // If more than one transaction holds the lock in SIX mode, and the current transaction also
        // is in SIX mode, the request succeeds, and transaction also becomes a lock holder.
        // If more than one transaction holds it, this is an indicator thereof.
        if (acquiredLock.mode == LockMode.SHARED_INTENSIONAL_EXCLUSIVE
            && data.getMode() == LockMode.SHARED_INTENSIONAL_EXCLUSIVE) {
          holders.add(transaction);
        }
      }
    }
    transactionData.get(transaction).registerLock(acquiredLock);
  }

  /**
   * Releases {@code lock} if it held by the {@code lockHolder} transaction.
   *
   * @param lock - {@link Lock}
   * @param lockHolder - {@link Transaction}
   */
  public synchronized void releaseLock(Lock<E> lock, Transaction<E> lockHolder) {
    // Transaction must be registered
    var data = transactionData.get(lockHolder);
    checkArgument(data != null, "Transaction is not currently active!");
    var locks = data.getHeldLocks();
    checkArgument(locks.contains(lock), "Transaction does not hold the lock!");

    // Update locking information
    data.unregisterLock(lock);
    // Remove lockHolder
    var lockData1 = lockData.get(lock);
    lockData1.getHolders().remove(lockHolder);
    // If no transactions hold the lock, remove the lock as well
    if (lockData1.getHolders().isEmpty()) {
      lockData.remove(lock);
    }
  }

  /**
   * Commits {@code transaction}, assuming that all its operations have been executed,
   * and all its locks have been released.
   *
   * <p>Upon that point, we update the {@code waitsForGraph} relation, and return all
   * transactions that are now unblocked.
   *
   * @param transaction - {@link Transaction}
   * @return {@link Collection}
   */
  public synchronized Collection<Transaction<E>> commit(Transaction<E> transaction) {
    checkArgument(transaction.getStatus() == TransactionStatus.RUNNING,
        "Only running transactions can be committed!");
    checkArgument(!transaction.wantsToAcquireLocks(),
        "Only transactions that have no more operations can be committed!");
    checkForReleaseOfAllLocks(transaction);
    // Mark commit
    transaction.setToCommited();
    return unblockTransactionsBlockedBy(transaction);
  }

  private List<Transaction<E>> unblockTransactionsBlockedBy(Transaction<E> transaction) {
    // Cleanup
    transactionData.remove(transaction);
    // Collect unblocked transactions
    return transactionData
        .entrySet()
        .stream()
        .filter(entry -> entry.getValue().unblock(transaction))
        .map(Map.Entry::getKey)
        .toList();
  }

  private void checkForReleaseOfAllLocks(Transaction<E> transaction) {
    var data = transactionData.get(transaction);
    checkArgument(data.getHeldLocks().isEmpty(),
        "Only transactions that do not have locks can be commited!");
  }

  /**
   * Aborts an aborting {@code transaction}, assuming that all its executed operations
   * have been inverted and all its locks have been released.
   *
   * <p>Upon that point, we update the {@code waitsForGraph} relation, and return all
   * transactions that are now unblocked.
   *
   * @param transaction - {@link Transaction}
   * @return {@link Collection}
   */
  public synchronized Collection<Transaction<E>> abort(Transaction<E> transaction) {
    checkArgument(transaction.getStatus() == TransactionStatus.ABORTING,
        "Only aborting transactions may be aborted!");
    checkArgument(!transaction.hasOperationsToInvert(),
        "Transaction still has operations to invert!");
    checkForReleaseOfAllLocks(transaction);
    // Mark abort
    transaction.setToAborted();
    return unblockTransactionsBlockedBy(transaction);
  }

  /**
   * Computes locks for the next operation of {@code transaction},
   * which must be running at this point.
   *
   * @param transaction - {@link Transaction}
   * @return {@link List}
   */
  public List<Lock<E>> computeNextLocksFor(Transaction<E> transaction) {
    var nextOperation = transaction.peekNextOperationForExecutionChecking();
    return LockComputer.computeLocksFor(nextOperation);
  }

}
