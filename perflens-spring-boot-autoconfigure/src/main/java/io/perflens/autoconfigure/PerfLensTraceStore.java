package io.perflens.autoconfigure;

import io.perflens.core.context.HibernateEvent;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.SpanSnapshot;
import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persists and reads completed PerfLens reports.
 *
 * <p>All methods run under the PerfLens ignore scope. Report persistence and
 * viewer queries must never be mistaken for SQL issued by the business API being
 * analyzed.</p>
 */
public final class PerfLensTraceStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PerfLensTraceStore(DataSource dataSource, Clock clock) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /**
     * Saves a completed trace report.
     *
     * @param report report to save
     */
    public void save(TraceReport report) {
        PerfLensTraceContext.runIgnoring(() -> {
            try {
                insertTrace(report);
                for (SpanSnapshot span : report.spans()) {
                    insertSpan(report.traceId(), span);
                }
                for (SqlEvent event : report.sqlEvents()) {
                    insertSql(report.traceId(), event);
                }
                for (HibernateEvent event : report.hibernateEvents()) {
                    insertHibernate(report.traceId(), event);
                }
                for (TraceFinding finding : report.findings()) {
                    insertFinding(report.traceId(), finding);
                }
            }
            catch (DataAccessException ex) {
                // PerfLens is an observer. Storage failures must never break the business API.
            }
        });
    }

    /**
     * Reads a compact trace summary for the viewer.
     *
     * @param traceId trace id
     * @return saved trace when found
     */
    public Optional<StoredTrace> findTrace(String traceId) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select trace_id, target_key, label, route, http_method, status_code,
                               duration_ms, query_count, hibernate_event_count, finding_count, started_at, ended_at
                        from perflens_trace
                        where trace_id = ?
                        """, rs -> {
                    if (!rs.next()) {
                        return Optional.<StoredTrace>empty();
                    }
                    return Optional.of(new StoredTrace(
                            rs.getString("trace_id"),
                            rs.getString("target_key"),
                            rs.getString("label"),
                            rs.getString("route"),
                            rs.getString("http_method"),
                            (Integer) rs.getObject("status_code"),
                            rs.getDouble("duration_ms"),
                            rs.getInt("query_count"),
                            rs.getInt("hibernate_event_count"),
                            rs.getInt("finding_count"),
                            rs.getTimestamp("started_at").toInstant(),
                            rs.getTimestamp("ended_at").toInstant()));
                }, traceId);
            }
            catch (DataAccessException ex) {
                return Optional.<StoredTrace>empty();
            }
        });
    }

    /**
     * Reads recent trace summaries for the report list.
     *
     * @param limit maximum rows to return
     * @return recent traces ordered newest first
     */
    public List<StoredTrace> findRecentTraces(int limit) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select trace_id, target_key, label, route, http_method, status_code,
                               duration_ms, query_count, hibernate_event_count, finding_count, started_at, ended_at
                        from perflens_trace
                        order by started_at desc
                        limit ?
                        """, (rs, rowNum) -> new StoredTrace(
                        rs.getString("trace_id"),
                        rs.getString("target_key"),
                        rs.getString("label"),
                        rs.getString("route"),
                        rs.getString("http_method"),
                        (Integer) rs.getObject("status_code"),
                        rs.getDouble("duration_ms"),
                        rs.getInt("query_count"),
                        rs.getInt("hibernate_event_count"),
                        rs.getInt("finding_count"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant()), Math.max(limit, 0));
            }
            catch (DataAccessException ex) {
                return List.<StoredTrace>of();
            }
        });
    }

    /**
     * Reads SQL rows for a trace.
     *
     * @param traceId trace id
     * @return SQL rows in execution order
     */
    public List<StoredSqlEvent> findSqlEvents(String traceId) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select ordinal_value, sql_text, normalized_sql, duration_ms, success, error_message
                        from perflens_sql_event
                        where trace_id = ?
                        order by ordinal_value
                        """, (rs, rowNum) -> new StoredSqlEvent(rs.getInt("ordinal_value"), rs.getString("sql_text"),
                        rs.getString("normalized_sql"), rs.getDouble("duration_ms"), rs.getBoolean("success"),
                        rs.getString("error_message")), traceId);
            }
            catch (DataAccessException ex) {
                return List.<StoredSqlEvent>of();
            }
        });
    }

    /**
     * Reads Hibernate lifecycle rows for a trace.
     *
     * @param traceId trace id
     * @return Hibernate rows in event order
     */
    public List<StoredHibernateEvent> findHibernateEvents(String traceId) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select ordinal_value, event_type, entity_name, collection_role, detail_text
                        from perflens_hibernate_event
                        where trace_id = ?
                        order by ordinal_value
                        """, (rs, rowNum) -> new StoredHibernateEvent(rs.getInt("ordinal_value"),
                        rs.getString("event_type"), rs.getString("entity_name"), rs.getString("collection_role"),
                        rs.getString("detail_text")), traceId);
            }
            catch (DataAccessException ex) {
                return List.<StoredHibernateEvent>of();
            }
        });
    }

    /**
     * Reads generated findings for a trace.
     *
     * @param traceId trace id
     * @return findings in storage order
     */
    public List<StoredFinding> findFindings(String traceId) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select finding_type, severity, message_text, evidence_text
                        from perflens_finding
                        where trace_id = ?
                        order by id
                        """, (rs, rowNum) -> new StoredFinding(rs.getString("finding_type"),
                        rs.getString("severity"), rs.getString("message_text"), rs.getString("evidence_text")),
                        traceId);
            }
            catch (DataAccessException ex) {
                return List.<StoredFinding>of();
            }
        });
    }

    /**
     * Deletes traces older than the configured retention window.
     *
     * @param maxAge cutoff duration
     * @return number of trace rows removed
     */
    public int deleteOlderThan(java.time.Duration maxAge) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                Instant cutoff = clock.instant().minus(maxAge);
                Timestamp cutoffTimestamp = Timestamp.from(cutoff);
                Integer count = jdbcTemplate.queryForObject("select count(*) from perflens_trace where ended_at < ?",
                        Integer.class, cutoffTimestamp);
                jdbcTemplate.update("""
                        delete from perflens_sql_event
                        where trace_id in (select trace_id from perflens_trace where ended_at < ?)
                        """, cutoffTimestamp);
                jdbcTemplate.update("""
                        delete from perflens_hibernate_event
                        where trace_id in (select trace_id from perflens_trace where ended_at < ?)
                        """, cutoffTimestamp);
                jdbcTemplate.update("""
                        delete from perflens_finding
                        where trace_id in (select trace_id from perflens_trace where ended_at < ?)
                        """, cutoffTimestamp);
                jdbcTemplate.update("""
                        delete from perflens_span
                        where trace_id in (select trace_id from perflens_trace where ended_at < ?)
                        """, cutoffTimestamp);
                jdbcTemplate.update("delete from perflens_trace where ended_at < ?", cutoffTimestamp);
                jdbcTemplate.update("""
                        insert into perflens_retention_run(started_at, completed_at, deleted_trace_count)
                        values (?, ?, ?)
                        """, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                        count == null ? 0 : count);
                return count == null ? 0 : count;
            }
            catch (DataAccessException ex) {
                return 0;
            }
        });
    }

    private void insertTrace(TraceReport report) {
        jdbcTemplate.update("""
                insert into perflens_trace(trace_id, target_key, label, boundary, route, http_method, status_code,
                  error_message, started_at, ended_at, duration_ms, query_count, hibernate_event_count, finding_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.traceId(), report.targetKey(), report.label(), report.boundary().name(), report.route(),
                report.httpMethod(), report.statusCode(), report.errorMessage(), Timestamp.from(report.startedAt()),
                Timestamp.from(report.endedAt()), report.durationMillis(), report.sqlEvents().size(),
                report.hibernateEvents().size(), report.findings().size());
    }

    private void insertSpan(String traceId, SpanSnapshot span) {
        jdbcTemplate.update("""
                insert into perflens_span(span_id, trace_id, parent_span_id, target_key, label, boundary,
                  started_at, ended_at, duration_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, span.spanId(), traceId, span.parentSpanId().orElse(null), span.targetKey(), span.label(),
                span.boundary().name(), Timestamp.from(span.startedAt()), Timestamp.from(span.endedAt()),
                span.durationMillis());
    }

    private void insertSql(String traceId, SqlEvent event) {
        jdbcTemplate.update("""
                insert into perflens_sql_event(trace_id, span_id, ordinal_value, sql_text, normalized_sql,
                  duration_ms, success, error_message)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, traceId, event.spanId(), event.ordinal(), event.sql(), event.normalizedSql(),
                event.durationMillis(), event.success(), event.errorMessage());
    }

    private void insertHibernate(String traceId, HibernateEvent event) {
        jdbcTemplate.update("""
                insert into perflens_hibernate_event(trace_id, span_id, ordinal_value, event_type, entity_name,
                  collection_role, detail_text)
                values (?, ?, ?, ?, ?, ?, ?)
                """, traceId, event.spanId(), event.ordinal(), event.eventType().name(), event.entityName(),
                event.collectionRole(), event.detail());
    }

    private void insertFinding(String traceId, TraceFinding finding) {
        jdbcTemplate.update("""
                insert into perflens_finding(trace_id, finding_type, severity, message_text, evidence_text)
                values (?, ?, ?, ?, ?)
                """, traceId, finding.type(), finding.severity().name(), finding.message(), finding.evidence());
    }

    /**
     * Compact trace projection for the HTML viewer.
     */
    public record StoredTrace(String traceId, String targetKey, String label, String route, String httpMethod,
            Integer statusCode, double durationMillis, int queryCount, int hibernateEventCount, int findingCount,
            Instant startedAt, Instant endedAt) {
    }

    /**
     * Compact SQL projection for the HTML viewer.
     */
    public record StoredSqlEvent(int ordinal, String sql, String normalizedSql, double durationMillis, boolean success,
            String errorMessage) {
    }

    /**
     * Compact Hibernate event projection for the HTML viewer.
     */
    public record StoredHibernateEvent(int ordinal, String eventType, String entityName, String collectionRole,
            String detail) {
    }

    /**
     * Compact finding projection for the HTML viewer.
     */
    public record StoredFinding(String type, String severity, String message, String evidence) {
    }
}
