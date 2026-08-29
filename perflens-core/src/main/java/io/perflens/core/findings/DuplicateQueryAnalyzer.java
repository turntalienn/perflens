package io.perflens.core.findings;

import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Flags SELECTs that ran more than once with exactly the same SQL and the same
 * bound parameters within one trace. The second execution cannot return anything
 * new unless the data changed in between, so it is usually wasted work.
 *
 * <p>Sequence fetches ({@code nextval}) are ignored since repeating them is expected.</p>
 */
public final class DuplicateQueryAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "DUPLICATE_QUERY";

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        Map<Integer, List<SqlEvent>> byStatement = report.sqlEvents().stream()
                .filter(SqlEvent::success)
                .filter(SqlEvent::isSelect)
                .filter(event -> !event.normalizedSql().contains("nextval"))
                .collect(Collectors.groupingBy(event -> Objects.hash(event.sql(), event.parameterHash()),
                        LinkedHashMap::new, Collectors.toList()));

        List<TraceFinding> findings = new ArrayList<>();
        byStatement.values().stream()
                .filter(events -> events.size() > 1)
                .sorted(Comparator.comparingInt(List<SqlEvent>::size).reversed())
                .forEach(events -> findings.add(toFinding(events)));
        return findings;
    }

    private static TraceFinding toFinding(List<SqlEvent> events) {
        long totalNanos = events.stream().mapToLong(SqlEvent::durationNanos).sum();
        return new TraceFinding(TYPE, FindingSeverity.WARNING,
                "Identical query with identical parameters ran " + events.size() + " times. Reuse the first result "
                        + "instead of querying again.",
                events.size() + " executions, " + TraceAnalyzers.millis(totalNanos) + " total: "
                        + events.get(0).normalizedSql());
    }
}
