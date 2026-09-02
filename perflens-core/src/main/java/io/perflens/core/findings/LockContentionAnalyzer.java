package io.perflens.core.findings;

import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flags statements the database rejected because of row locks: deadlocks
 * (the database picked this transaction as the victim) and lock wait timeouts
 * (another transaction held the row for too long).
 *
 * <p>Recognizes MySQL/MariaDB, PostgreSQL, SQL Server, Oracle and H2 error codes,
 * plus the standard SQLState {@code 40001} for serialization failures.</p>
 */
public final class LockContentionAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "LOCK_CONTENTION";

    /** MySQL 1213, Oracle ORA-00060. SQL Server 1205 is matched by its message. */
    private static final Set<Integer> DEADLOCK_CODES = Set.of(1213, 60);
    /** MySQL 1205, SQL Server 1222, Oracle ORA-00054 and ORA-30006, H2 50200. */
    private static final Set<Integer> LOCK_TIMEOUT_CODES = Set.of(1205, 1222, 54, 30006, 50200);

    /** Kind of lock failure. */
    enum Kind { DEADLOCK, SERIALIZATION_FAILURE, LOCK_TIMEOUT }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        List<TraceFinding> findings = new ArrayList<>();
        for (SqlEvent event : report.sqlEvents()) {
            if (event.success()) {
                continue;
            }
            Kind kind = classify(event.sqlState(), event.vendorCode(), event.errorMessage());
            if (kind != null) {
                findings.add(toFinding(kind, event));
            }
        }
        return findings;
    }

    /**
     * @param sqlState SQLState, may be {@code null}
     * @param vendorCode driver error code
     * @param message exception message, may be {@code null}
     * @return the lock failure kind, or {@code null} when the error is not lock related
     */
    static Kind classify(String sqlState, int vendorCode, String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if ("40P01".equals(sqlState) || DEADLOCK_CODES.contains(vendorCode) || text.contains("deadlock")) {
            return Kind.DEADLOCK;
        }
        if ("55P03".equals(sqlState) || "HYT00".equals(sqlState) || LOCK_TIMEOUT_CODES.contains(vendorCode)
                || text.contains("lock wait timeout") || text.contains("timeout trying to lock")) {
            return Kind.LOCK_TIMEOUT;
        }
        return "40001".equals(sqlState) ? Kind.SERIALIZATION_FAILURE : null;
    }

    private static TraceFinding toFinding(Kind kind, SqlEvent event) {
        String evidence = "SQLState " + event.sqlState() + ", code " + event.vendorCode() + " after "
                + TraceAnalyzers.millis(event.durationNanos()) + ": " + event.normalizedSql();
        return switch (kind) {
            case DEADLOCK -> new TraceFinding(TYPE, FindingSeverity.CRITICAL,
                    "Deadlock: the database rolled this transaction back. Lock rows in a consistent order, keep "
                            + "transactions short, and index the columns in WHERE clauses so updates lock fewer rows "
                            + "(MySQL gap locks).",
                    evidence);
            case SERIALIZATION_FAILURE -> new TraceFinding(TYPE, FindingSeverity.WARNING,
                    "Serialization failure: a concurrent transaction changed the same data. Retry the transaction "
                            + "or reduce the isolation level.",
                    evidence);
            case LOCK_TIMEOUT -> new TraceFinding(TYPE, FindingSeverity.WARNING,
                    "Timed out waiting for a row lock held by another transaction. Find the long transaction "
                            + "holding it (often one doing remote calls) and shorten it.",
                    evidence);
        };
    }
}
