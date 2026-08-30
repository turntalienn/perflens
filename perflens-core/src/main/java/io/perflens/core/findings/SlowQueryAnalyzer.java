package io.perflens.core.findings;

import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Flags statements slower than a threshold, one finding per query shape.
 */
public final class SlowQueryAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "SLOW_QUERY";

    private final long thresholdNanos;

    /**
     * @param threshold statement duration that counts as slow
     */
    public SlowQueryAnalyzer(Duration threshold) {
        this.thresholdNanos = threshold.toNanos();
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        Map<String, List<SqlEvent>> slowByShape = report.sqlEvents().stream()
                .filter(event -> event.durationNanos() >= thresholdNanos)
                .collect(Collectors.groupingBy(SqlEvent::normalizedSql, LinkedHashMap::new, Collectors.toList()));

        List<TraceFinding> findings = new ArrayList<>();
        slowByShape.values().forEach(events -> {
            long maxNanos = events.stream().mapToLong(SqlEvent::durationNanos).max().orElse(0);
            FindingSeverity severity = maxNanos >= thresholdNanos * 10 ? FindingSeverity.CRITICAL
                    : FindingSeverity.WARNING;
            findings.add(new TraceFinding(TYPE, severity,
                    "Query took " + TraceAnalyzers.millis(maxNanos) + ". Check its execution plan and indexes.",
                    events.size() + " slow executions, max " + TraceAnalyzers.millis(maxNanos) + ": "
                            + events.get(0).normalizedSql()));
        });
        return findings;
    }
}
