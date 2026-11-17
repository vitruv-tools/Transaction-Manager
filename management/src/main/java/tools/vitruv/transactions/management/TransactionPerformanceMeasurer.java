package tools.vitruv.transactions.management;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.vitruv.transactions.management.scheduling.SchedulingEventObserver;

/**
 * Computes the following statistics about transactions:
 *
 * <ol>
 *   <li>latency, how long each transaction takes to finish (commit),</li>
 *   <li>throughput, how many transactions are executed per second,</li>
 *   <li>acceptance rate, how many transactions have been accepted.</li>
 * </ol>
 */
public class TransactionPerformanceMeasurer<E> implements SchedulingEventObserver<E> {
  /**
   * Time of the first transaction submission.
   */
  private long startTimeInNs = -1;
  /**
   * Time of the last commit.
   */
  private long lastCommitTimeInNs;

  /**
   * Set for active transactions.
   */
  private final Set<TransactionState<E>> activeTransactions = new HashSet<>();
  /**
   * Set for committed transactions.
   */
  private final Set<TransactionState<E>> commitedTransactions = new HashSet<>();
  /**
   * Set for aborted transactions.
   */
  private final Set<TransactionState<E>> abortedTransactions = new HashSet<>();
  /**
   * Start and end time sets, used to compute latencies.
   */
  private final Map<TransactionState<E>, TimingTuple> latencyData = new HashMap<>();

  /**
   * When a transaction starts, mark it as submitted, active, and create latency data.
   *
   * @param newTransaction {@link TransactionState}
   */
  @Override
  public void observeAdmission(TransactionState<E> newTransaction) {
    var now = System.nanoTime();
    if (startTimeInNs == -1) {
      startTimeInNs = now;
    }
    activeTransactions.add(newTransaction);
    latencyData.put(newTransaction, new TimingTuple(now));
  }

  /**
   * Marks {@code runningTransaction} as active (and also as not aborted).
   *
   * @param runningTransaction {@link TransactionState}
   */
  @Override
  public void observeRunning(TransactionState<E> runningTransaction) {
    abortedTransactions.remove(runningTransaction);
    activeTransactions.add(runningTransaction);
  }

  /**
   * Marks {@code aborted} as active (and also as not running anymore).
   *
   * @param aborting {@link TransactionState}
   */
  @Override
  public void observeAbort(TransactionState<E> aborting) {
    abortedTransactions.add(aborting);
    activeTransactions.remove(aborting);
  }

  /**
   * Marks {@code commited} as a commited transaction (not active anymore),
   * updates latency, and last-commit-time data.
   *
   * @param commited {@link TransactionState}
   */
  @Override
  public void observeCommit(TransactionState<E> commited) {
    var now = System.nanoTime();
    lastCommitTimeInNs = now;
    activeTransactions.remove(commited);
    commitedTransactions.add(commited);
    latencyData.get(commited).endTime = now;
  }

  /**
   * Returns the throughput in seconds.
   *
   * @return {@link BigDecimal}
   */
  public BigDecimal getThroughput() {
    checkState(startTimeInNs >= 0, "No transaction has been submitted so far!");
    return new BigDecimal(lastCommitTimeInNs - startTimeInNs)
        .divide(new BigDecimal(commitedTransactions.size()))
        .scaleByPowerOfTen(-9);
  }

  /**
   * Returns the latencies of all committed transactions.
   *
   * @return List<BigDecimal>
   */
  private List<BigDecimal> getLatencies() {
    return latencyData
        .entrySet()
        .stream()
        .filter(e -> e.getKey().getStatus() == TransactionStatus.COMMITED)
        .map(e -> {
          var timingTuple =  e.getValue();
          return new BigDecimal(timingTuple.endTime - timingTuple.startTime)
              .scaleByPowerOfTen(-9);
        })
        .toList();
  }

  /**
   * Returns acceptance rate information.
   *
   * @return new {@link AcceptanceRates}
   */
  public AcceptanceRates getAcceptanceRates() {
    return new AcceptanceRates(
        abortedTransactions.size(),
        commitedTransactions.size(),
        activeTransactions.size()
    );
  }

  /**
   * Timing tuple data.
   */
  private static class TimingTuple {
    long startTime;
    long endTime;

    private TimingTuple(long startTime) {
      this.startTime = startTime;
    }
  }

  /**
   * Acceptance rate information, which contains:
   *
   * <ol>
   *   <li>numbers about all active, submitted, aborted and commited transactions,</li>
   *   <li>relative values in percent.</li>
   * </ol>
   */
  public record AcceptanceRates(
      long all,
      long aborted,
      long commited,
      long active,
      BigDecimal abortedPercent,
      BigDecimal committedPercent,
      BigDecimal activePercent
  ) {
    public AcceptanceRates(long aborted, long commited, long active) {
      this(aborted + commited + active,
          aborted,
          commited,
          active,
          new BigDecimal(aborted).divide(new BigDecimal(aborted + commited + active)).scaleByPowerOfTen(2),
          new BigDecimal(commited).divide(new BigDecimal(aborted + commited + active)).scaleByPowerOfTen(2),
          new BigDecimal(active).divide(new BigDecimal(aborted + commited + active)).scaleByPowerOfTen(2));
    }
  };
}
