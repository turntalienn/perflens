package io.perflens.core.findings;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.context.TransactionEvent;
import io.perflens.core.model.FindingSeverity;
import java.time.Duration;
import java.util.List;

/**
 * Flags JDBC transactions that stayed open longer than a threshold.
 *
 * <p>The message separates the two usual causes: slow SQL inside the transaction,
 * or a transaction held open around non-database work (remote calls, file I/O,
 * heavy computation) while it pins a pooled connection.</p>
 */
public final class SlowTransactionAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "SLOW_TRANSACTION";

    private final long thresholdNanos;

    /**
     * @param threshold transaction duration that counts as slow
     */
    public SlowTransactionAnalyzer(Duration threshold) {
        this.thresholdNanos = threshold.toNanos();
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        return report.transactions().stream()
                .filter(tx -> tx.durationNanos() >= thresholdNanos)
                .map(this::toFinding)
                .toList();
    }

    private TraceFinding toFinding(TransactionEvent tx) {
        long outsideSql = tx.durationNanos() - tx.sqlNanos();
        String message = outsideSql * 2 > tx.durationNanos()
                ? "Transaction stayed open " + TraceAnalyzers.millis(tx.durationNanos()) + " but only "
                        + TraceAnalyzers.millis(tx.sqlNanos()) + " was SQL. Move remote calls and heavy work out of "
                        + "the transaction so the connection goes back to the pool sooner."
                : "Transaction took " + TraceAnalyzers.millis(tx.durationNanos()) + ", mostly in SQL. Look at the "
                        + "slow statements inside it.";
        FindingSeverity severity = tx.durationNanos() >= thresholdNanos * 10 ? FindingSeverity.CRITICAL
                : FindingSeverity.WARNING;
        return new TraceFinding(TYPE, severity, message,
                (tx.committed() ? "committed" : "rolled back") + " after " + TraceAnalyzers.millis(tx.durationNanos())
                        + ", " + tx.statementCount() + " statements, " + TraceAnalyzers.millis(tx.sqlNanos())
                        + " in SQL");
    }
}
