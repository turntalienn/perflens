package io.perflens.core.context;

/**
 * One JDBC transaction, from {@code setAutoCommit(false)} to commit or rollback.
 *
 * @param ordinal 1-based order within the trace
 * @param spanId span that was active when the transaction ended
 * @param durationNanos wall time the transaction was open
 * @param statementCount statements executed inside the transaction
 * @param sqlNanos time spent inside the driver for those statements
 * @param committed {@code true} for commit, {@code false} for rollback or close without commit
 */
public record TransactionEvent(
        int ordinal,
        String spanId,
        long durationNanos,
        int statementCount,
        long sqlNanos,
        boolean committed) {

    /** @return time the transaction was open, in milliseconds */
    public double durationMillis() {
        return durationNanos / 1_000_000.0;
    }

    /** @return time spent executing SQL, in milliseconds */
    public double sqlMillis() {
        return sqlNanos / 1_000_000.0;
    }
}
