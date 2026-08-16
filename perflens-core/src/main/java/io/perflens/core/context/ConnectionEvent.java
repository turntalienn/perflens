package io.perflens.core.context;

/**
 * One connection checkout from the pool, captured under a trace.
 *
 * <p>Pool gauges are read right after the checkout. They are {@code -1} when the
 * pool does not expose them (only HikariCP is read today).</p>
 *
 * @param ordinal 1-based order within the trace
 * @param spanId span that was active at checkout
 * @param waitNanos time spent inside {@code DataSource.getConnection()}
 * @param heldByTrace connections this trace held at once, including this one
 * @param poolActive connections in use across the whole pool
 * @param poolMax configured maximum pool size
 * @param poolPending threads blocked waiting for a connection
 */
public record ConnectionEvent(
        int ordinal,
        String spanId,
        long waitNanos,
        int heldByTrace,
        int poolActive,
        int poolMax,
        int poolPending) {

    /** @return checkout wait in milliseconds */
    public double waitMillis() {
        return waitNanos / 1_000_000.0;
    }
}
