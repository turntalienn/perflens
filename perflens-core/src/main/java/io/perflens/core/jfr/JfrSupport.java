package io.perflens.core.jfr;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;

/**
 * Emits PerfLens data as Java Flight Recorder events, so traces show up in JDK
 * Mission Control next to GC pauses, allocation samples and thread states.
 *
 * <p>Event types: {@code io.perflens.Trace} (one per traced request, on the request
 * thread), {@code io.perflens.Sql} (one per statement, with the stack trace that
 * issued it) and {@code io.perflens.Finding}. Nothing is recorded unless a JFR
 * recording is running, e.g. {@code -XX:StartFlightRecording} or
 * {@code jcmd <pid> JFR.start}. Disable with {@link #setEnabled(boolean)}.</p>
 */
public final class JfrSupport {

    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();
    private static volatile boolean enabled = true;

    private JfrSupport() {
    }

    /**
     * @param value whether PerfLens emits JFR events
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** @return whether events are emitted (JFR present and not disabled) */
    public static boolean isEnabled() {
        return AVAILABLE && enabled;
    }

    /**
     * Starts a trace event. Call on the thread that opens the trace.
     *
     * @return handle for {@link #commitTrace}, or {@code null} when JFR is off
     */
    public static Object beginTrace() {
        return isEnabled() ? Events.begin(new TraceEvent()) : null;
    }

    /**
     * Ends and commits a trace event. Call on the thread that opened it.
     *
     * @param handle handle from {@link #beginTrace}, may be {@code null}
     * @param report the closed report
     */
    public static void commitTrace(Object handle, TraceReport report) {
        if (handle instanceof TraceEvent event) {
            Events.commitTrace(event, report);
        }
    }

    /**
     * Starts an SQL event. Call right before the statement executes.
     *
     * @return handle for {@link #commitSql}, or {@code null} when JFR is off
     */
    public static Object beginSql() {
        return isEnabled() ? Events.begin(new SqlEvent()) : null;
    }

    /**
     * Ends and commits an SQL event.
     *
     * @param handle handle from {@link #beginSql}, may be {@code null}
     * @param traceId id of the trace the statement belongs to
     * @param sql statement text
     * @param success whether it succeeded
     */
    public static void commitSql(Object handle, String traceId, String sql, boolean success) {
        if (handle instanceof SqlEvent event) {
            Events.commitSql(event, traceId, sql, success);
        }
    }

    /**
     * Emits one instant event per finding of an analyzed report.
     *
     * @param report analyzed report
     */
    public static void emitFindings(TraceReport report) {
        if (isEnabled() && !report.findings().isEmpty()) {
            Events.findings(report);
        }
    }

    /** Holds every reference to jdk.jfr so the module is only touched when present. */
    private static final class Events {

        private static <T extends jdk.jfr.Event> T begin(T event) {
            if (!event.isEnabled()) {
                return null;
            }
            event.begin();
            return event;
        }

        private static void commitTrace(TraceEvent event, TraceReport report) {
            event.end();
            if (!event.shouldCommit()) {
                return;
            }
            event.traceId = report.traceId();
            event.targetKey = report.targetKey();
            event.route = report.route();
            event.httpMethod = report.httpMethod();
            event.statusCode = report.statusCode() == null ? 0 : report.statusCode();
            event.sqlCount = report.sqlEvents().size();
            event.sqlTime = Math.round(report.sqlMillis() * 1_000_000);
            event.transactionCount = report.transactions().size();
            event.connectionCount = report.connections().size();
            event.entitiesLoaded = report.totalEntityLoads();
            event.allocatedBytes = report.resources().allocatedBytes();
            event.cpuTime = report.resources().cpuNanos();
            event.commit();
        }

        private static void commitSql(SqlEvent event, String traceId, String sql, boolean success) {
            event.end();
            if (event.shouldCommit()) {
                event.traceId = traceId;
                event.sql = sql;
                event.success = success;
                event.commit();
            }
        }

        private static void findings(TraceReport report) {
            for (TraceFinding finding : report.findings()) {
                FindingEvent event = new FindingEvent();
                if (!event.shouldCommit()) {
                    return;
                }
                event.traceId = report.traceId();
                event.targetKey = report.targetKey();
                event.findingType = finding.type();
                event.severity = finding.severity().name();
                event.message = finding.message();
                event.evidence = finding.evidence();
                event.commit();
            }
        }
    }
}
