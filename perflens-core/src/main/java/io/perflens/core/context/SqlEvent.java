package io.perflens.core.context;

/**
 * One JDBC statement execution captured under a trace.
 *
 * @param ordinal 1-based execution order within the trace
 * @param spanId span that was active when the statement ran
 * @param sql SQL text as handed to the driver
 * @param normalizedSql {@code sql} with literals and IN-lists collapsed, used to group query shapes
 * @param parameterHash hash of the bound parameter values, {@code 0} when nothing was bound;
 *                      the values themselves are never kept
 * @param durationNanos time spent inside the driver's {@code execute*} call
 * @param success whether the call returned normally
 * @param errorMessage exception message when {@code success} is false
 * @param sqlState SQLState of the failure, or {@code null}
 * @param vendorCode driver-specific error code of the failure, or {@code 0}
 */
public record SqlEvent(
        int ordinal,
        String spanId,
        String sql,
        String normalizedSql,
        int parameterHash,
        long durationNanos,
        boolean success,
        String errorMessage,
        String sqlState,
        int vendorCode) {

    /** @return execution time in milliseconds */
    public double durationMillis() {
        return durationNanos / 1_000_000.0;
    }

    /** @return whether this is a read query (SELECT or WITH ... SELECT) */
    public boolean isSelect() {
        return normalizedSql.startsWith("select") || normalizedSql.startsWith("with");
    }
}
