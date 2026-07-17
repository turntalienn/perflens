package io.perflens.core.context;

/**
 * Immutable SQL execution evidence captured under an active trace.
 *
 * @param ordinal per-trace execution order
 * @param spanId span active when the SQL executed
 * @param sql SQL text seen by the collector
 * @param normalizedSql SQL normalized for grouping repeated query shapes
 * @param durationNanos elapsed execution time in nanoseconds
 * @param success whether the JDBC call completed without throwing
 * @param errorMessage exception message when the execution failed
 */
public record SqlEvent(
        int ordinal,
        String spanId,
        String sql,
        String normalizedSql,
        long durationNanos,
        boolean success,
        String errorMessage) {

    /**
     * @return elapsed execution time in milliseconds
     */
    public double durationMillis() {
        return durationNanos / 1_000_000.0;
    }
}
