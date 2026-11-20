package tools.vitruv.transactions.management.locking.deadlock;

import java.util.List;
import lombok.Getter;
import tools.vitruv.transactions.management.TransactionState;
import tools.vitruv.transactions.management.locking.S2PLLockManager;

/**
 * Thrown to the transaction that was chosen as victim to break a deadlock. The transaction keeps
 * its locks until it ends: the caller has to undo its changes and then call
 * {@link S2PLLockManager#endTransaction(TransactionState, boolean)} with {@code committed = false}
 * (to resubmit it) or {@link S2PLLockManager#cancelTransaction(TransactionState)}.
 */
public class DeadlockVictimException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * The detected wait-for cycle, starting with the transaction whose request closed it.
   */
  @Getter
  private final transient List<? extends TransactionState<?>> cycle;

  /**
   * Creates a new {@link DeadlockVictimException}.
   *
   * @param cycle - the wait-for cycle
   */
  public DeadlockVictimException(List<? extends TransactionState<?>> cycle) {
    super("Transaction chosen as deadlock victim; wait-for cycle of length " + cycle.size());
    this.cycle = List.copyOf(cycle);
  }
}
