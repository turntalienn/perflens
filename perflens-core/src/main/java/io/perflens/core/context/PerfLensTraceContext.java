package io.perflens.core.context;

import io.perflens.core.findings.NPlusOneFindingAnalyzer;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound holder for the currently active PerfLens trace.
 *
 * <p>PerfLens uses this context to keep per-call evidence isolated. JDBC and
 * Hibernate collectors record events only when this holder contains an active
 * trace and the current thread is not inside an internal ignore scope.</p>
 */
public final class PerfLensTraceContext {

    private static final ThreadLocal<TraceState> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> IGNORE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final NPlusOneFindingAnalyzer DEFAULT_ANALYZER = new NPlusOneFindingAnalyzer();

    private PerfLensTraceContext() {
    }

    /**
     * Opens a new root trace on the current thread.
     *
     * @param targetKey stable annotation key
     * @param label display label
     * @param boundary root boundary
     * @param route HTTP route pattern, if any
     * @param httpMethod HTTP method, if any
     * @param clock clock used for deterministic tests and production timestamps
     * @return active root trace
     */
    public static ActiveTrace startRoot(String targetKey, String label, TraceBoundary boundary, String route,
            String httpMethod, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        ActiveTrace trace = new ActiveTrace(targetKey, label, boundary, route, httpMethod, clock.instant());
        ActiveSpan rootSpan = new ActiveSpan(null, targetKey, label, boundary, trace.startedAt());
        TraceState state = new TraceState(trace, rootSpan);
        CURRENT.set(state);
        return trace;
    }

    /**
     * Opens a nested span under the active trace.
     *
     * @param targetKey nested annotation key
     * @param label display label
     * @param boundary nested boundary
     * @param clock clock used for the span start time
     * @return active child span
     */
    public static ActiveSpan startChildSpan(String targetKey, String label, TraceBoundary boundary, Clock clock) {
        TraceState state = requireState();
        ActiveSpan parent = state.currentSpan();
        ActiveSpan child = new ActiveSpan(parent.spanId(), targetKey, label, boundary, clock.instant());
        state.openSpan(child);
        return child;
    }

    /**
     * Closes the currently active span without closing the root trace.
     *
     * @param clock clock used for the span end time
     */
    public static void closeCurrentSpan(Clock clock) {
        currentState().ifPresent(state -> state.closeCurrentSpan(clock.instant()));
    }

    /**
     * Records a SQL execution if a trace is active and the current thread is not
     * inside an ignore scope.
     *
     * @param sql SQL text
     * @param durationNanos execution duration in nanoseconds
     * @param success whether execution succeeded
     * @param errorMessage error message when execution failed
     */
    public static void recordSql(String sql, long durationNanos, boolean success, String errorMessage) {
        if (isIgnoring()) {
            return;
        }
        currentState().ifPresent(state -> state.recordSql(sql, durationNanos, success, errorMessage));
    }

    /**
     * Records a Hibernate event if a trace is active and collection is not
     * currently ignored.
     *
     * @param eventType event type
     * @param entityName entity name when available
     * @param collectionRole collection role when available
     * @param detail extra human-readable detail
     */
    public static void recordHibernateEvent(HibernateEventType eventType, String entityName, String collectionRole,
            String detail) {
        if (isIgnoring()) {
            return;
        }
        currentState().ifPresent(state -> state.recordHibernateEvent(eventType, entityName, collectionRole, detail));
    }

    /**
     * Closes the root trace and returns an immutable report.
     *
     * @param statusCode HTTP status code, if available
     * @param errorMessage error message, if the call failed
     * @param clock clock used for the trace end time
     * @return completed report
     */
    public static TraceReport closeRoot(Integer statusCode, String errorMessage, Clock clock) {
        TraceState state = requireState();
        Instant endedAt = clock.instant();
        TraceReport report = state.close(statusCode, errorMessage, endedAt);
        CURRENT.remove();
        return withFindings(report, DEFAULT_ANALYZER.analyze(report));
    }

    /**
     * Runs an operation while collectors are suppressed.
     *
     * <p>PerfLens wraps its own control queries, report persistence, retention
     * cleanup, and viewer queries in this method so the tool never measures
     * itself as part of a business API trace.</p>
     *
     * @param action action to run
     */
    public static void runIgnoring(Runnable action) {
        int previous = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(previous + 1);
        try {
            action.run();
        }
        finally {
            if (previous == 0) {
                IGNORE_DEPTH.remove();
            }
            else {
                IGNORE_DEPTH.set(previous);
            }
        }
    }

    /**
     * Runs an operation while collectors are suppressed and returns its value.
     *
     * @param supplier operation to run
     * @param <T> return type
     * @return supplier result
     */
    public static <T> T callIgnoring(ThrowingSupplier<T> supplier) {
        int previous = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(previous + 1);
        try {
            return supplier.get();
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        finally {
            if (previous == 0) {
                IGNORE_DEPTH.remove();
            }
            else {
                IGNORE_DEPTH.set(previous);
            }
        }
    }

    /**
     * @return current active trace, if one is bound to this thread
     */
    public static Optional<ActiveTrace> currentTrace() {
        return currentState().map(state -> state.trace);
    }

    /**
     * @return current active span, if one is bound to this thread
     */
    public static Optional<ActiveSpan> currentSpan() {
        return currentState().map(TraceState::currentSpan);
    }

    /**
     * @return whether the current thread is inside an internal ignore scope
     */
    public static boolean isIgnoring() {
        return IGNORE_DEPTH.get() > 0;
    }

    /**
     * Clears all thread-local state. Tests and container shutdown hooks can use
     * this as a defensive cleanup operation.
     */
    public static void clear() {
        CURRENT.remove();
        IGNORE_DEPTH.remove();
    }

    private static Optional<TraceState> currentState() {
        return Optional.ofNullable(CURRENT.get());
    }

    private static TraceState requireState() {
        TraceState state = CURRENT.get();
        if (state == null) {
            throw new IllegalStateException("No active PerfLens trace is bound to the current thread.");
        }
        return state;
    }

    private static TraceReport withFindings(TraceReport report, List<TraceFinding> findings) {
        return new TraceReport(report.traceId(), report.targetKey(), report.label(), report.boundary(), report.route(),
                report.httpMethod(), report.statusCode(), report.errorMessage(), report.startedAt(), report.endedAt(),
                report.spans(), report.sqlEvents(), report.hibernateEvents(), List.copyOf(findings));
    }

    /**
     * Supplier variant that allows checked exceptions while preserving a compact
     * public API for ignore-scope operations.
     *
     * @param <T> return type
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        /**
         * @return operation result
         * @throws Exception when the operation fails
         */
        T get() throws Exception;
    }

    private static final class TraceState {

        private final ActiveTrace trace;
        private final List<ActiveSpan> spans = new ArrayList<>();
        private final Deque<ActiveSpan> stack = new ArrayDeque<>();
        private final List<SqlEvent> sqlEvents = new ArrayList<>();
        private final List<HibernateEvent> hibernateEvents = new ArrayList<>();
        private int sqlOrdinal;
        private int hibernateOrdinal;

        private TraceState(ActiveTrace trace, ActiveSpan rootSpan) {
            this.trace = trace;
            this.spans.add(rootSpan);
            this.stack.push(rootSpan);
        }

        private ActiveSpan currentSpan() {
            return stack.peek();
        }

        private void openSpan(ActiveSpan span) {
            spans.add(span);
            stack.push(span);
        }

        private void closeCurrentSpan(Instant endedAt) {
            if (stack.size() <= 1) {
                return;
            }
            stack.pop().close(endedAt);
        }

        private void recordSql(String sql, long durationNanos, boolean success, String errorMessage) {
            sqlEvents.add(new SqlEvent(++sqlOrdinal, currentSpan().spanId(), sql, SqlNormalizer.normalize(sql),
                    durationNanos, success, errorMessage));
        }

        private void recordHibernateEvent(HibernateEventType eventType, String entityName, String collectionRole,
                String detail) {
            hibernateEvents.add(new HibernateEvent(++hibernateOrdinal, currentSpan().spanId(), eventType, entityName,
                    collectionRole, detail));
        }

        private TraceReport close(Integer statusCode, String errorMessage, Instant endedAt) {
            while (!stack.isEmpty()) {
                ActiveSpan span = stack.pop();
                if (span.endedAt().isEmpty()) {
                    span.close(endedAt);
                }
            }
            List<SpanSnapshot> snapshots = spans.stream()
                    .map(span -> span.snapshot(endedAt))
                    .toList();
            return new TraceReport(trace.traceId(), trace.targetKey(), trace.label(), trace.boundary(), trace.route(),
                    trace.httpMethod(), statusCode, errorMessage, trace.startedAt(), endedAt,
                    Collections.unmodifiableList(snapshots), List.copyOf(sqlEvents), List.copyOf(hibernateEvents),
                    List.of());
        }
    }
}
