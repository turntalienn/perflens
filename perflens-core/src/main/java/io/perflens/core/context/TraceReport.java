package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything captured for one traced call. Immutable; produced by
 * {@link PerfLensTraceContext#closeRoot} and enriched with findings by the analyzers.
 *
 * @param traceId unique id
 * @param targetKey key of the root boundary
 * @param label display label of the root boundary
 * @param boundary root boundary kind
 * @param route MVC route pattern, or {@code null} for service-method roots
 * @param httpMethod HTTP method, or {@code null} for service-method roots
 * @param statusCode HTTP status, or {@code null} for service-method roots
 * @param errorMessage exception class name when the call failed, otherwise {@code null}
 * @param startedAt start time
 * @param endedAt end time
 * @param spans root span first, then nested spans in opening order
 * @param sqlEvents captured statements in execution order
 * @param hibernateEvents captured Hibernate events in firing order, except entity loads
 * @param entityLoads entities Hibernate loaded, by entity name (counted, not listed one by one)
 * @param transactions JDBC transactions that ended during the trace
 * @param connections connection checkouts made during the trace
 * @param resources heap allocation, CPU and GC during the trace
 * @param droppedEvents SQL and Hibernate events discarded after the per-trace cap was hit
 * @param findings analyzer output, empty until analyzed
 */
public record TraceReport(
        String traceId,
        String targetKey,
        String label,
        TraceBoundary boundary,
        String route,
        String httpMethod,
        Integer statusCode,
        String errorMessage,
        Instant startedAt,
        Instant endedAt,
        List<SpanSnapshot> spans,
        List<SqlEvent> sqlEvents,
        List<HibernateEvent> hibernateEvents,
        Map<String, Integer> entityLoads,
        List<TransactionEvent> transactions,
        List<ConnectionEvent> connections,
        ResourceUsage resources,
        int droppedEvents,
        List<TraceFinding> findings) {

    /** @return wall time of the whole trace in milliseconds */
    public double durationMillis() {
        return Duration.between(startedAt, endedAt).toNanos() / 1_000_000.0;
    }

    /** @return time spent inside the JDBC driver across all captured statements, in milliseconds */
    public double sqlMillis() {
        return sqlEvents.stream().mapToLong(SqlEvent::durationNanos).sum() / 1_000_000.0;
    }

    /** @return entities Hibernate loaded across all entity types */
    public int totalEntityLoads() {
        return entityLoads.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * @param type finding type, for example {@code NPlusOneAnalyzer.TYPE}
     * @return findings of that type
     */
    public List<TraceFinding> findingsOfType(String type) {
        return findings.stream().filter(finding -> finding.type().equals(type)).toList();
    }

    /**
     * @param newFindings findings to attach
     * @return a copy of this report carrying {@code newFindings}
     */
    public TraceReport withFindings(List<TraceFinding> newFindings) {
        return new TraceReport(traceId, targetKey, label, boundary, route, httpMethod, statusCode, errorMessage,
                startedAt, endedAt, spans, sqlEvents, hibernateEvents, entityLoads, transactions, connections, resources,
                droppedEvents, List.copyOf(newFindings));
    }
}
