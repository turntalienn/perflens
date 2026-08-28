package io.perflens.core.findings;

import io.perflens.core.context.HibernateEvent;
import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import io.perflens.core.model.HibernateEventType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Flags N+1 query patterns: the same SELECT shape running many times with
 * different parameters, typically one query per parent row.
 *
 * <p>Executions with identical parameters are left to {@link DuplicateQueryAnalyzer}.
 * When Hibernate initialized a lazy collection role just as often, that role is
 * named in the evidence because it is the usual cause.</p>
 */
public final class NPlusOneAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "N_PLUS_ONE";

    private final int threshold;

    /**
     * @param threshold distinct executions of one shape that count as N+1
     */
    public NPlusOneAnalyzer(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        Map<String, List<SqlEvent>> byShape = report.sqlEvents().stream()
                .filter(SqlEvent::success)
                .filter(SqlEvent::isSelect)
                .collect(Collectors.groupingBy(SqlEvent::normalizedSql, LinkedHashMap::new, Collectors.toList()));
        String lazyCollections = lazyCollections(report);

        List<TraceFinding> findings = new ArrayList<>();
        byShape.values().stream()
                .filter(events -> distinctExecutions(events) >= threshold)
                .sorted(Comparator.comparingInt(List<SqlEvent>::size).reversed())
                .forEach(events -> findings.add(toFinding(events, lazyCollections)));
        return findings;
    }

    private static long distinctExecutions(List<SqlEvent> events) {
        return events.stream().map(event -> Objects.hash(event.sql(), event.parameterHash())).distinct().count();
    }

    private String lazyCollections(TraceReport report) {
        return report.hibernateEvents().stream()
                .filter(event -> event.eventType() == HibernateEventType.COLLECTION_INIT)
                .map(HibernateEvent::collectionRole)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(role -> role, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private TraceFinding toFinding(List<SqlEvent> events, String lazyCollections) {
        long totalNanos = events.stream().mapToLong(SqlEvent::durationNanos).sum();
        String evidence = events.size() + " executions, " + TraceAnalyzers.millis(totalNanos) + " total: "
                + events.get(0).normalizedSql();
        if (!lazyCollections.isEmpty()) {
            evidence += " | lazy collections initialized: " + lazyCollections;
        }
        FindingSeverity severity = events.size() >= threshold * 10 ? FindingSeverity.CRITICAL : FindingSeverity.WARNING;
        return new TraceFinding(TYPE, severity,
                "Same query ran " + events.size() + " times with different parameters. Load the rows in one query "
                        + "(join fetch, @EntityGraph, @BatchSize or an IN query).",
                evidence);
    }
}
