package tools.vitruv.transactions.management.locking;

import java.util.List;
import lombok.Getter;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Thrown to the transaction that the {@link LockManager} chose as victim to break a deadlock.
 * The transaction keeps its locks until it ends: the caller has to undo its changes and then call
 * {@link LockManager#endTransaction(TransactionState, boolean)} with {@code committed = false}.
 * Resubmitting the same change keeps the transaction's age, so it is not chosen again forever.
 */
public class DeadlockVictimException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * The detected wait-for cycle, starting with the victim.
   */
  @Getter
  private final transient List<? extends TransactionState<?>> cycle;

  /**
   * Creates a new {@link DeadlockVictimException}.
   *
   * @param cycle - the wait-for cycle, starting with the victim
   */
  public DeadlockVictimException(List<? extends TransactionState<?>> cycle) {
    super("Transaction chosen as deadlock victim; wait-for cycle of length " + cycle.size());
    this.cycle = List.copyOf(cycle);
  }
}
