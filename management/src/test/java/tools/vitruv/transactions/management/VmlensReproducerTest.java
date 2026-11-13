package tools.vitruv.transactions.management;

import com.vmlens.api.AllInterleavings;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Temporary reproducer: mimics the threading pattern of AbstractScheduler without EMF/Vitruv.
 */

class VmlensReproducerTest {
  private final Object lockManager = new Object();
  private int lockHolder = -1;

  /** Each task tries to take a shared "lock"; on conflict it reports BLOCKED (false). */
  private boolean task(int id) {
    synchronized (lockManager) {
      if (lockHolder != -1) {
        return false;
      }      lockHolder = id;
    }
    synchronized (lockManager) {
      lockHolder = -1;
    }
    return true;
  }

  private void submit(ExecutorService pool, BlockingDeque<int[]> results, int id) {
    CompletableFuture.supplyAsync(() -> {
      try {
        results.put(new int[] {id, task(id) ? 1 : 0});
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return true;
    }, pool).exceptionally(t -> false);
  }

  @Test
  void schedulerPattern() throws InterruptedException {
    try (AllInterleavings interleavings = new AllInterleavings("reproducerSchedulerPattern")) {
      var iteration = 0;
      while (interleavings.hasNext()) {
        System.out.println("Reproducer interleaving " + ++iteration);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BlockingDeque<int[]> results = new LinkedBlockingDeque<>();
        int open = 4;
        for (int i = 0; i < open; i++) {
          submit(pool, results, i);
        }
        while (open > 0) {
          var result = results.take();
          if (result[1] == 1) {
            open--;
          } else {
            // Blocked: resubmit, like the scheduler does for unblocked transactions
            submit(pool, results, result[0]);
          }
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
      }
    }
  }
}
