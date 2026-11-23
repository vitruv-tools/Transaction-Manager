package tools.vitruv.transactions.management.locking.deadlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.vitruv.transactions.management.TransactionState;

/**
 * The data and decisions of deadlock handling for a lock manager: the wait-for graph (which
 * transaction waits for which others), the age of each transaction and the victims chosen by a
 * {@link VictimSelectionStrategy}.
 *
 * <p>Not thread-safe on its own: the lock manager calls it while holding its monitor.
 *
 * @param <E> - The Element type.
 */
public class DeadlockDetector<E> {
  private final VictimSelectionStrategy<E> strategy;
  /**
   * Wait-for edges: the transactions each waiting transaction waits for.
   */
  private final Map<TransactionState<E>, Set<TransactionState<E>>> waitsFor = new HashMap<>();
  /**
   * Age of each transaction (lower = older), by the order of first admission. Kept while a
   * transaction may still be resubmitted (it equals its aborted predecessor), removed when it
   * commits or is cancelled.
   */
  private final Map<TransactionState<E>, Long> admissionOrder = new HashMap<>();
  private long nextAdmission = 0;
  /**
   * Transactions chosen as victims that have not noticed it yet.
   */
  private final Set<TransactionState<E>> victims = new HashSet<>();

  /**
   * Creates a new {@link DeadlockDetector}.
   *
   * @param strategy - {@link VictimSelectionStrategy}
   */
  public DeadlockDetector(VictimSelectionStrategy<E> strategy) {
    this.strategy = strategy;
  }

  /**
   * Registers a submitted transaction; a resubmitted one keeps its age.
   *
   * @param transaction - {@link TransactionState}
   */
  public void admit(TransactionState<E> transaction) {
    admissionOrder.putIfAbsent(transaction, nextAdmission++);
  }

  /**
   * Records that {@code transaction} now waits for {@code blockers} (replacing earlier edges: a
   * transaction waits for one request at a time), searches for a cycle through it, and if there
   * is one, lets the strategy choose a victim.
   *
   * @param transaction - {@link TransactionState}
   * @param blockers - the transactions holding a requested lock in an incompatible mode
   * @return the victim, if a deadlock was found
   */
  public Optional<TransactionState<E>> waitFor(TransactionState<E> transaction,
      Collection<TransactionState<E>> blockers) {
    if (blockers.isEmpty()) {
      waitsFor.remove(transaction);
      return Optional.empty();
    }
    waitsFor.put(transaction, new HashSet<>(blockers));
    return findCycle(transaction).map(cycle -> {
      var victim = strategy.chooseVictim(cycle,
          t -> admissionOrder.getOrDefault(t, Long.MAX_VALUE));
      victims.add(victim);
      return victim;
    });
  }

  /**
   * If {@code transaction} was chosen as victim, forgets that choice and its wait-for edges and
   * throws.
   *
   * @param transaction - {@link TransactionState}
   * @throws DeadlockVictimException if {@code transaction} is a victim
   */
  public void throwIfVictim(TransactionState<E> transaction) throws DeadlockVictimException {
    if (victims.remove(transaction)) {
      var cycle = findCycle(transaction).orElse(List.of(transaction));
      waitsFor.remove(transaction);
      throw new DeadlockVictimException(cycle);
    }
  }

  /**
   * Forgets the wait-for edges and victim mark of an ended transaction.
   *
   * @param transaction - {@link TransactionState}
   * @param mayBeResubmitted - false if the transaction committed or is cancelled; its age is
   *                         forgotten as well
   */
  public void end(TransactionState<E> transaction, boolean mayBeResubmitted) {
    waitsFor.remove(transaction);
    victims.remove(transaction);
    if (!mayBeResubmitted) {
      admissionOrder.remove(transaction);
    }
  }

  /**
   * The transactions that {@code transaction} currently waits for.
   *
   * @param transaction - {@link TransactionState}
   * @return {@link Set}
   */
  public Set<TransactionState<E>> waitsFor(TransactionState<E> transaction) {
    return Set.copyOf(waitsFor.getOrDefault(transaction, Set.of()));
  }

  /**
   * Whether the age of {@code transaction} is still kept (it may be resubmitted).
   *
   * @param transaction - {@link TransactionState}
   * @return boolean
   */
  public boolean knows(TransactionState<E> transaction) {
    return admissionOrder.containsKey(transaction);
  }

  /**
   * Searches the wait-for graph for a cycle through {@code transaction}.
   *
   * @param transaction - {@link TransactionState}
   * @return the transactions on the cycle, starting with {@code transaction}, or empty
   */
  public Optional<List<TransactionState<E>>> findCycle(TransactionState<E> transaction) {
    // depth-first search for a path from the successors of transaction back to it
    Map<TransactionState<E>, TransactionState<E>> predecessor = new HashMap<>();
    Deque<TransactionState<E>> stack = new ArrayDeque<>();
    stack.push(transaction);
    Set<TransactionState<E>> visited = new HashSet<>();
    while (!stack.isEmpty()) {
      var current = stack.pop();
      if (!visited.add(current)) {
        continue;
      }
      for (var next : waitsFor.getOrDefault(current, Set.of())) {
        if (next.equals(transaction)) {
          var cycle = new ArrayList<TransactionState<E>>();
          for (var t = current; t != null; t = predecessor.get(t)) {
            cycle.add(0, t);
          }
          return Optional.of(cycle);
        }
        if (!visited.contains(next)) {
          predecessor.putIfAbsent(next, current);
          stack.push(next);
        }
      }
    }
    return Optional.empty();
  }
}
