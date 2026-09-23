package io.perflens.autoconfigure.app;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs per-plate work on Spring's task executor, like a parallel PI calculation.
 * The barrier keeps all three transactions open at once so the test is deterministic.
 */
@Service
public class PlateWorker {

    private final JdbcTemplate jdbcTemplate;
    private final CyclicBarrier allRunning = new CyclicBarrier(3);

    public PlateWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    @Transactional(readOnly = true)
    public CompletableFuture<Integer> process(int plate) throws Exception {
        Integer count = jdbcTemplate.queryForObject("select count(*) from book where id <> ?", Integer.class, plate);
        allRunning.await(5, TimeUnit.SECONDS);
        return CompletableFuture.completedFuture(count);
    }
}
