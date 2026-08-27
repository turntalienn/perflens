package io.perflens.core.findings;

import java.time.Duration;
import java.util.Objects;

/**
 * Thresholds used by the built-in analyzers.
 *
 * @param nPlusOneExecutions executions of one query shape, with different parameters, that count as N+1
 * @param slowQuery statement duration that counts as slow
 * @param slowTransaction transaction duration that counts as slow
 * @param connectionWait connection checkout wait that counts as pool pressure
 * @param allocationBytes heap allocated by one trace that counts as high
 * @param gcTime GC pause time overlapping one trace that counts as GC pressure
 * @param entityLoads entities loaded by one trace that count as a large persistence context
 */
public record AnalysisThresholds(
        int nPlusOneExecutions,
        Duration slowQuery,
        Duration slowTransaction,
        Duration connectionWait,
        long allocationBytes,
        Duration gcTime,
        int entityLoads) {

    /** Validates the values. */
    public AnalysisThresholds {
        if (nPlusOneExecutions < 2) {
            throw new IllegalArgumentException("nPlusOneExecutions must be at least 2");
        }
        if (allocationBytes < 1 || entityLoads < 1) {
            throw new IllegalArgumentException("allocationBytes and entityLoads must be positive");
        }
        Objects.requireNonNull(slowQuery, "slowQuery");
        Objects.requireNonNull(slowTransaction, "slowTransaction");
        Objects.requireNonNull(connectionWait, "connectionWait");
        Objects.requireNonNull(gcTime, "gcTime");
    }

    /**
     * @return 4 executions, 200 ms query, 500 ms transaction, 50 ms connection wait,
     *         64 MB allocation, 100 ms GC, 1,000 entities
     */
    public static AnalysisThresholds defaults() {
        return new AnalysisThresholds(4, Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofMillis(50),
                64L * 1024 * 1024, Duration.ofMillis(100), 1_000);
    }
}
