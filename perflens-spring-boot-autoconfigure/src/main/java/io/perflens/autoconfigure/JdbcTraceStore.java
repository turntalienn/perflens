package io.perflens.autoconfigure;

import io.perflens.core.context.HibernateEvent;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.SpanSnapshot;
import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saves reports to the {@code perflens_*} tables and reads them back for the viewer.
 *
 * <p>Each report is written in its own transaction with one batch per table, so it
 * never joins or disturbs an application transaction. Write failures are logged
 * and swallowed. Traces older than the retention period are deleted at most once
 * an hour, piggybacking on saves instead of needing a scheduler.</p>
 */
public final class JdbcTraceStore implements TraceReportListener {

    private static final Log logger = LogFactory.getLog(JdbcTraceStore.class);
    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofHours(1).toNanos();
    private static final String TRACE_COLUMNS = """
            trace_id, target_key, label, boundary, route, http_method, status_code, error_message, started_at,
            duration_micros, sql_count, sql_micros, transaction_count, connection_count, hibernate_event_count,
            finding_count, max_severity, dropped_events, allocated_bytes, cpu_micros, gc_count, gc_millis,
            entity_load_count""";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final Duration retention;
    private final AtomicLong nextCleanup = new AtomicLong(System.nanoTime());
    private volatile boolean warned;

    /**
     * @param dataSource DataSource holding the PerfLens tables
     * @param clock time source for retention
     * @param retention age after which traces are deleted
     */
    public JdbcTraceStore(DataSource dataSource, Clock clock, Duration retention) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.retention = retention;
    }

    @Override
    public void onReport(TraceReport report) {
        PerfLensTraceContext.runIgnoring(() -> {
            try {
                ownTransaction.executeWithoutResult(status -> insert(report));
                deleteExpiredIfDue();
            }
            catch (DataAccessException ex) {
                if (!warned) {
                    warned = true;
                    logger.warn("PerfLens could not save trace reports (is the schema installed?): " + ex.getMessage());
                }
                else {
                    logger.debug("PerfLens could not save trace " + report.traceId(), ex);
                }
            }
        });
    }

    /**
     * Deletes traces that started before {@code cutoff}, with their child rows.
     *
     * @param cutoff traces started before this instant are removed
     * @return number of traces removed
     */
    public int deleteStartedBefore(Instant cutoff) {
        Timestamp before = Timestamp.from(cutoff);
        return PerfLensTraceContext.callIgnoring(() -> ownTransaction.execute(status -> {
            for (String table : List.of("perflens_sql_event", "perflens_hibernate_event", "perflens_finding",
                    "perflens_span")) {
                jdbcTemplate.update("delete from " + table
                        + " where trace_id in (select trace_id from perflens_trace where started_at < ?)", before);
            }
            return jdbcTemplate.update("delete from perflens_trace where started_at < ?", before);
        }));
    }

    /**
     * @param limit maximum rows
     * @return most recent traces first
     */
    public List<StoredTrace> findRecentTraces(int limit) {
        return read(() -> jdbcTemplate.query("select " + TRACE_COLUMNS
                + " from perflens_trace order by started_at desc limit ?", JdbcTraceStore::mapTrace, limit),
                List.of());
    }

    /**
     * @param traceId trace id
     * @return the trace summary, if saved
     */
    public Optional<StoredTrace> findTrace(String traceId) {
        return read(() -> jdbcTemplate.query("select " + TRACE_COLUMNS + " from perflens_trace where trace_id = ?",
                JdbcTraceStore::mapTrace, traceId).stream().findFirst(), Optional.empty());
    }

    /**
     * @param traceId trace id
     * @return statements in execution order
     */
    public List<StoredSql> findSql(String traceId) {
        return read(() -> jdbcTemplate.query("""
                select seq, sql_text, duration_micros, success, error_message
                from perflens_sql_event where trace_id = ? order by seq
                """, (rs, row) -> new StoredSql(rs.getInt("seq"), rs.getString("sql_text"),
                rs.getLong("duration_micros") / 1000.0, rs.getBoolean("success"), rs.getString("error_message")),
                traceId), List.of());
    }

    /**
     * @param traceId trace id
     * @return Hibernate events in firing order
     */
    public List<StoredHibernateEvent> findHibernateEvents(String traceId) {
        return read(() -> jdbcTemplate.query("""
                select seq, event_type, entity_name, collection_role, detail
                from perflens_hibernate_event where trace_id = ? order by seq
                """, (rs, row) -> new StoredHibernateEvent(rs.getInt("seq"), rs.getString("event_type"),
                rs.getString("entity_name"), rs.getString("collection_role"), rs.getString("detail")), traceId),
                List.of());
    }

    /**
     * @param traceId trace id
     * @return findings, most severe first
     */
    public List<StoredFinding> findFindings(String traceId) {
        return read(() -> jdbcTemplate.query("""
                select finding_type, severity, message, evidence
                from perflens_finding where trace_id = ? order by seq
                """, (rs, row) -> new StoredFinding(rs.getString("finding_type"), rs.getString("severity"),
                rs.getString("message"), rs.getString("evidence")), traceId), List.of());
    }

    private void insert(TraceReport report) {
        String id = report.traceId();
        jdbcTemplate.update("insert into perflens_trace(" + TRACE_COLUMNS
                + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, report.targetKey(), truncate(report.label(), 240), report.boundary().name(),
                truncate(report.route(), 360),
                report.httpMethod(), report.statusCode(), truncate(report.errorMessage(), 1000),
                Timestamp.from(report.startedAt()), micros(report.durationMillis()), report.sqlEvents().size(),
                micros(report.sqlMillis()), report.transactions().size(), report.connections().size(),
                report.hibernateEvents().size(), report.findings().size(),
                report.findings().isEmpty() ? null : report.findings().get(0).severity().name(),
                report.droppedEvents(), report.resources().allocatedBytes(),
                report.resources().cpuNanos() < 0 ? -1 : report.resources().cpuNanos() / 1000,
                report.resources().gcCount(), report.resources().gcMillis(), report.totalEntityLoads());

        List<Object[]> spans = new ArrayList<>();
        for (SpanSnapshot span : report.spans()) {
            spans.add(new Object[] {id, span.spanId(), span.parentSpanId(), span.targetKey(),
                    truncate(span.label(), 240), span.boundary().name(), Timestamp.from(span.startedAt()),
                    micros(span.durationMillis())});
        }
        batch("""
                insert into perflens_span(trace_id, span_id, parent_span_id, target_key, label, boundary,
                  started_at, duration_micros) values (?, ?, ?, ?, ?, ?, ?, ?)""", spans);

        List<Object[]> sql = new ArrayList<>();
        for (SqlEvent event : report.sqlEvents()) {
            sql.add(new Object[] {id, event.ordinal(), event.spanId(), truncate(event.sql(), 10_000),
                    micros(event.durationMillis()), event.success(), truncate(event.errorMessage(), 1000)});
        }
        batch("""
                insert into perflens_sql_event(trace_id, seq, span_id, sql_text, duration_micros, success,
                  error_message) values (?, ?, ?, ?, ?, ?, ?)""", sql);

        List<Object[]> hibernate = new ArrayList<>();
        for (HibernateEvent event : report.hibernateEvents()) {
            hibernate.add(new Object[] {id, event.ordinal(), event.spanId(), event.eventType().name(),
                    truncate(event.entityName(), 255), truncate(event.collectionRole(), 255),
                    truncate(event.detail(), 255)});
        }
        int loadSeq = report.hibernateEvents().size();
        for (Map.Entry<String, Integer> load : report.entityLoads().entrySet()) {
            hibernate.add(new Object[] {id, ++loadSeq, report.spans().get(0).spanId(), "ENTITY_LOAD",
                    truncate(load.getKey(), 255), null, "loaded " + load.getValue()});
        }
        batch("""
                insert into perflens_hibernate_event(trace_id, seq, span_id, event_type, entity_name,
                  collection_role, detail) values (?, ?, ?, ?, ?, ?, ?)""", hibernate);

        List<Object[]> findings = new ArrayList<>();
        int seq = 0;
        for (TraceFinding finding : report.findings()) {
            findings.add(new Object[] {id, ++seq, finding.type(), finding.severity().name(),
                    truncate(finding.message(), 1000), truncate(finding.evidence(), 10_000)});
        }
        batch("""
                insert into perflens_finding(trace_id, seq, finding_type, severity, message, evidence)
                values (?, ?, ?, ?, ?, ?)""", findings);
    }

    private void batch(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, rows);
        }
    }

    private void deleteExpiredIfDue() {
        long now = System.nanoTime();
        long due = nextCleanup.get();
        if (now - due >= 0 && nextCleanup.compareAndSet(due, now + CLEANUP_INTERVAL_NANOS)) {
            deleteStartedBefore(clock.instant().minus(retention));
        }
    }

    private static <T> T read(PerfLensTraceContext.ThrowingSupplier<T> query, T fallback) {
        try {
            return PerfLensTraceContext.callIgnoring(query);
        }
        catch (DataAccessException ex) {
            logger.debug("PerfLens read failed", ex);
            return fallback;
        }
    }

    private static StoredTrace mapTrace(ResultSet rs, int row) throws SQLException {
        return new StoredTrace(rs.getString("trace_id"), rs.getString("target_key"), rs.getString("label"),
                rs.getString("route"), rs.getString("http_method"), (Integer) rs.getObject("status_code"),
                rs.getString("error_message"), rs.getTimestamp("started_at").toInstant(),
                rs.getLong("duration_micros") / 1000.0, rs.getInt("sql_count"), rs.getLong("sql_micros") / 1000.0,
                rs.getInt("transaction_count"), rs.getInt("connection_count"), rs.getInt("hibernate_event_count"),
                rs.getInt("finding_count"), rs.getString("max_severity"), rs.getInt("dropped_events"),
                rs.getLong("allocated_bytes"), rs.getLong("cpu_micros"), rs.getLong("gc_count"),
                rs.getLong("gc_millis"), rs.getInt("entity_load_count"));
    }

    private static long micros(double millis) {
        return Math.round(millis * 1000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Saved trace summary.
     *
     * @param traceId trace id
     * @param targetKey root key
     * @param label root label
     * @param route MVC route, or {@code null}
     * @param httpMethod HTTP method, or {@code null}
     * @param statusCode HTTP status, or {@code null}
     * @param errorMessage failure, or {@code null}
     * @param startedAt start time
     * @param durationMillis wall time
     * @param sqlCount statements executed
     * @param sqlMillis driver time
     * @param transactionCount transactions
     * @param connectionCount connection checkouts
     * @param hibernateEventCount Hibernate events
     * @param findingCount findings
     * @param maxSeverity most severe finding, or {@code null}
     * @param droppedEvents events beyond the per-trace cap
     * @param allocatedBytes heap allocated, or -1
     * @param cpuMicros CPU time, or -1
     * @param gcCount GC pauses during the trace
     * @param gcMillis GC pause time during the trace
     * @param entityLoadCount entities Hibernate loaded
     */
    public record StoredTrace(String traceId, String targetKey, String label, String route, String httpMethod,
            Integer statusCode, String errorMessage, Instant startedAt, double durationMillis, int sqlCount,
            double sqlMillis, int transactionCount, int connectionCount, int hibernateEventCount, int findingCount,
            String maxSeverity, int droppedEvents, long allocatedBytes, long cpuMicros, long gcCount, long gcMillis,
            int entityLoadCount) {
    }

    /**
     * Saved statement.
     *
     * @param seq execution order
     * @param sql SQL text, truncated to 10,000 characters
     * @param durationMillis driver time
     * @param success whether it succeeded
     * @param errorMessage failure, or {@code null}
     */
    public record StoredSql(int seq, String sql, double durationMillis, boolean success, String errorMessage) {
    }

    /**
     * Saved Hibernate event.
     *
     * @param seq firing order
     * @param eventType event type name
     * @param entityName entity, or {@code null}
     * @param collectionRole collection role, or {@code null}
     * @param detail detail text
     */
    public record StoredHibernateEvent(int seq, String eventType, String entityName, String collectionRole,
            String detail) {
    }

    /**
     * Saved finding.
     *
     * @param type finding type
     * @param severity severity name
     * @param message explanation
     * @param evidence supporting numbers and SQL
     */
    public record StoredFinding(String type, String severity, String message, String evidence) {
    }
}
