package tools.vitruv.transactions.management.locking.deadlock;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import tools.vitruv.transactions.management.TransactionState;

/**
 * Chooses which transaction of a deadlock (a cycle in the wait-for graph) is aborted.
 *
 * @param <E> - The Element type.
 */
@FunctionalInterface
public interface VictimSelectionStrategy<E> {
  /**
   * Chooses the victim among the transactions of {@code cycle}.
   *
   * @param cycle - the wait-for cycle, starting with the transaction whose request closed it
   * @param age - the age of a transaction by its first admission (lower = older); it survives
   *            aborts, as a resubmitted transaction equals its predecessor
   * @return a transaction of {@code cycle}
   */
  TransactionState<E> chooseVictim(List<TransactionState<E>> cycle,
      ToLongFunction<TransactionState<E>> age);

  /**
   * Youngest dies: the transaction admitted last is aborted, so the least work is lost, and a
   * restarted victim, which keeps its age, eventually is the oldest and is not chosen again (no
   * starvation).
   *
   * @param <E> - The Element type.
   * @return {@link VictimSelectionStrategy}
   */
  static <E> VictimSelectionStrategy<E> youngestDies() {
    return (cycle, age) -> cycle.stream().max(Comparator.comparingLong(age)).orElseThrow();
  }

  /**
   * Requester dies: the transaction whose lock request closed the cycle is aborted. Needs no ages,
   * but a transaction may be chosen again and again.
   *
   * @param <E> - The Element type.
   * @return {@link VictimSelectionStrategy}
   */
  static <E> VictimSelectionStrategy<E> requesterDies() {
    return (cycle, age) -> cycle.get(0);
  }
}
