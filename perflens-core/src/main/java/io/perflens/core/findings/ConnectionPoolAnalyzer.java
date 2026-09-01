package io.perflens.core.findings;

import io.perflens.core.context.ConnectionEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Flags connection pool pressure seen by one trace.
 *
 * <ul>
 *   <li>Saturation: a checkout waited longer than the threshold, or every pooled
 *       connection was in use. Critical when threads were also queued on the pool.
 *       Threads queued for a moment during a short wait are normal and ignored.</li>
 *   <li>Multiple connections: the trace held more than one connection at once,
 *       usually {@code REQUIRES_NEW} inside an open transaction or parallel work on
 *       worker threads. Under load every request holds one connection while
 *       waiting for more, which can drain the pool completely.</li>
 * </ul>
 */
public final class ConnectionPoolAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "CONNECTION_POOL_PRESSURE";

    private final long waitThresholdNanos;

    /**
     * @param waitThreshold checkout wait that counts as pressure
     */
    public ConnectionPoolAnalyzer(Duration waitThreshold) {
        this.waitThresholdNanos = waitThreshold.toNanos();
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        List<ConnectionEvent> checkouts = report.connections();
        if (checkouts.isEmpty()) {
            return List.of();
        }
        long maxWait = checkouts.stream().mapToLong(ConnectionEvent::waitNanos).max().orElse(0);
        long totalWait = checkouts.stream().mapToLong(ConnectionEvent::waitNanos).sum();
        int maxPending = checkouts.stream().mapToInt(ConnectionEvent::poolPending).max().orElse(-1);
        boolean poolExhausted = checkouts.stream()
                .anyMatch(event -> event.poolMax() > 0 && event.poolActive() >= event.poolMax());
        int maxHeld = checkouts.stream().mapToInt(ConnectionEvent::heldByTrace).max().orElse(0);

        List<TraceFinding> findings = new ArrayList<>();
        boolean longWait = maxWait >= waitThresholdNanos;
        if (longWait || poolExhausted) {
            ConnectionEvent worst = checkouts.stream()
                    .max((a, b) -> Long.compare(a.waitNanos(), b.waitNanos())).orElseThrow();
            String pool = worst.poolMax() < 0 ? "pool gauges unavailable"
                    : "pool active " + worst.poolActive() + "/" + worst.poolMax() + ", waiting threads " + maxPending;
            boolean queued = longWait && maxPending > 0;
            findings.add(new TraceFinding(TYPE, queued ? FindingSeverity.CRITICAL : FindingSeverity.WARNING,
                    "Connection pool is under pressure. Shorten transactions, or size the pool for the load.",
                    checkouts.size() + " checkouts, max wait " + TraceAnalyzers.millis(maxWait) + ", total wait "
                            + TraceAnalyzers.millis(totalWait) + ", " + pool));
        }
        if (maxHeld > 1) {
            findings.add(new TraceFinding(TYPE, FindingSeverity.WARNING,
                    "Request held " + maxHeld + " connections at once (REQUIRES_NEW inside an open transaction, "
                            + "or parallel work on worker threads). Under load this can exhaust the pool.",
                    checkouts.size() + " checkouts, up to " + maxHeld + " held at the same time"));
        }
        return findings;
    }
}
