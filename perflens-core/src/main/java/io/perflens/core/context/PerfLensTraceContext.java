package io.perflens.core.context;

import io.perflens.core.jfr.JfrSupport;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Holds the trace open on the current thread and collects events into it.
 *
 * <p>Boundary adapters open and close traces ({@link #startRoot}, {@link #closeRoot}).
 * Collectors call the {@code record*} methods, which do nothing when no trace is
 * open or the thread is inside {@link #runIgnoring}/{@link #callIgnoring}, the scope
 * PerfLens uses for its own queries so it never measures itself.</p>
 *
 * <p>A trace belongs to the thread that opened it. Tasks handed to other threads
 * join it when wrapped with {@link #wrap(Runnable)} or {@link #wrap(Callable)};
 * their SQL, connections, allocation and CPU then count toward the same report.
 * Events that arrive after the trace closed are dropped.</p>
 */
public final class PerfLensTraceContext {

    /** Default cap on SQL plus Hibernate events kept per trace. */
    public static final int DEFAULT_MAX_EVENTS = 2_000;

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<int[]> IGNORE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private static volatile int maxEvents = DEFAULT_MAX_EVENTS;

    private PerfLensTraceContext() {
    }

    /**
     * Caps how many SQL and Hibernate events one trace keeps, bounding memory for
     * batch-like calls. Events past the cap are counted in
     * {@link TraceReport#droppedEvents()} but not stored. Entity loads are always
     * counted and never use up the cap.
     *
     * @param limit maximum events per trace, at least 1
     */
    public static void setMaxEventsPerTrace(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        maxEvents = limit;
    }

    /**
     * Opens a trace on the current thread, replacing any trace left behind.
     *
     * @param targetKey key of the boundary
     * @param label display label, may be empty
     * @param boundary boundary kind
     * @param route MVC route pattern, or {@code null}
     * @param httpMethod HTTP method, or {@code null}
     * @param clock time source
     * @return id of the new trace
     */
    public static String startRoot(String targetKey, String label, TraceBoundary boundary, String route,
            String httpMethod, Clock clock) {
        TraceState state = new TraceState(targetKey, label, boundary, route, httpMethod, clock.instant());
        CURRENT.set(new Binding(state, state.root, true));
        return state.traceId;
    }

    /**
     * Opens a span nested under the current span.
     *
     * @param targetKey key of the nested boundary
     * @param label display label, may be empty
     * @param boundary boundary kind
     * @param clock time source
     * @return id of the new span
     * @throws IllegalStateException when no trace is open
     */
    public static String startChildSpan(String targetKey, String label, TraceBoundary boundary, Clock clock) {
        Binding binding = requireBinding();
        Span child = new Span(binding.currentSpan().id, targetKey, label, boundary, clock.instant());
        binding.state.addSpan(child);
        binding.stack.push(child);
        return child.id;
    }

    /**
     * Closes the innermost span opened on this thread. Does nothing for the root
     * span, the span a worker task joined, or when no trace is open.
     *
     * @param clock time source
     */
    public static void closeCurrentSpan(Clock clock) {
        Binding binding = CURRENT.get();
        if (binding != null && binding.stack.size() > 1) {
            binding.stack.pop().endedAt = clock.instant();
        }
    }

    /**
     * Closes the trace on this thread and returns its report without findings.
     *
     * @param statusCode HTTP status, or {@code null}
     * @param errorMessage failure description, or {@code null}
     * @param clock time source
     * @return the completed report
     * @throws IllegalStateException when no trace is open, or this thread only joined it
     */
    public static TraceReport closeRoot(Integer statusCode, String errorMessage, Clock clock) {
        Binding binding = requireBinding();
        if (!binding.owner) {
            throw new IllegalStateException("Only the thread that opened a PerfLens trace can close it");
        }
        CURRENT.remove();
        TraceReport report = binding.state.close(statusCode, errorMessage, clock.instant());
        JfrSupport.commitTrace(binding.state.jfrEvent, report);
        return report;
    }

    /**
     * Drops the trace on this thread without producing a report. Used when a request
     * goes async and its completion will happen on another thread.
     */
    public static void discard() {
        Binding binding = CURRENT.get();
        CURRENT.remove();
        if (binding != null && binding.owner) {
            binding.state.discard();
        }
    }

    /**
     * Records one statement execution.
     *
     * @param sql SQL text
     * @param parameterHash hash of bound values, {@code 0} when none
     * @param durationNanos driver time
     * @param failure what the driver threw, or {@code null} on success
     */
    public static void recordSql(String sql, int parameterHash, long durationNanos, Throwable failure) {
        Binding binding = collectingBinding();
        if (binding != null) {
            binding.state.addSql(binding.currentSpan().id, sql, parameterHash, durationNanos, failure);
        }
    }

    /**
     * Records one Hibernate lifecycle event. {@link HibernateEventType#ENTITY_LOAD}
     * events are counted per entity name rather than stored individually.
     *
     * @param eventType event category
     * @param entityName entity name, or {@code null}
     * @param collectionRole collection role, or {@code null}
     * @param detail short detail text
     */
    public static void recordHibernateEvent(HibernateEventType eventType, String entityName, String collectionRole,
            String detail) {
        Binding binding = collectingBinding();
        if (binding != null) {
            binding.state.addHibernateEvent(binding.currentSpan().id, eventType, entityName, collectionRole, detail);
        }
    }

    /**
     * Records a finished JDBC transaction.
     *
     * @param durationNanos time the transaction was open
     * @param statementCount statements executed inside it
     * @param sqlNanos driver time of those statements
     * @param committed whether it committed
     */
    public static void recordTransaction(long durationNanos, int statementCount, long sqlNanos, boolean committed) {
        Binding binding = collectingBinding();
        if (binding != null) {
            binding.state.addTransaction(binding.currentSpan().id, durationNanos, statementCount, sqlNanos, committed);
        }
    }

    /**
     * Records a connection checkout and counts it as held by the trace until
     * {@link #recordConnectionReleased()}.
     *
     * @param waitNanos time spent in {@code getConnection()}
     * @param poolActive pool-wide active connections, or {@code -1}
     * @param poolMax pool maximum size, or {@code -1}
     * @param poolPending threads waiting on the pool, or {@code -1}
     * @return {@code true} if the checkout was counted; only then call {@link #recordConnectionReleased()}
     */
    public static boolean recordConnectionAcquired(long waitNanos, int poolActive, int poolMax, int poolPending) {
        Binding binding = collectingBinding();
        return binding != null
                && binding.state.addConnection(binding.currentSpan().id, waitNanos, poolActive, poolMax, poolPending);
    }

    /** Marks one connection counted by {@link #recordConnectionAcquired} as returned. */
    public static void recordConnectionReleased() {
        Binding binding = CURRENT.get();
        if (binding != null) {
            binding.state.releaseConnection();
        }
    }

    /**
     * Makes {@code task} join the trace open on the calling thread when it runs on
     * another thread. Returns {@code task} unchanged when no trace is open.
     *
     * @param task work to hand to an executor
     * @return the wrapped task
     */
    public static Runnable wrap(Runnable task) {
        Binding parent = CURRENT.get();
        if (parent == null || task instanceof JoinedTask) {
            return task;
        }
        return new JoinedRunnable(parent.state, parent.currentSpan(), task);
    }

    /**
     * Callable variant of {@link #wrap(Runnable)}.
     *
     * @param task work to hand to an executor
     * @param <T> result type
     * @return the wrapped task
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Binding parent = CURRENT.get();
        if (parent == null || task instanceof JoinedTask) {
            return task;
        }
        return new JoinedCallable<>(parent.state, parent.currentSpan(), task);
    }

    /**
     * Runs {@code action} with collection suppressed on this thread.
     *
     * @param action work to run
     */
    public static void runIgnoring(Runnable action) {
        int[] depth = IGNORE_DEPTH.get();
        depth[0]++;
        try {
            action.run();
        }
        finally {
            depth[0]--;
        }
    }

    /**
     * Runs {@code supplier} with collection suppressed on this thread.
     *
     * @param supplier work to run
     * @param <T> result type
     * @return the supplier's result
     */
    public static <T> T callIgnoring(ThrowingSupplier<T> supplier) {
        int[] depth = IGNORE_DEPTH.get();
        depth[0]++;
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
            depth[0]--;
        }
    }

    /** @return whether a trace is open on (or joined by) this thread */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** @return whether a trace is open and collection is not suppressed */
    public static boolean isCollecting() {
        return collectingBinding() != null;
    }

    /** @return id of the trace open on this thread */
    public static Optional<String> currentTraceId() {
        return Optional.ofNullable(CURRENT.get()).map(binding -> binding.state.traceId);
    }

    /** @return key of the innermost open span on this thread */
    public static Optional<String> currentSpanKey() {
        return Optional.ofNullable(CURRENT.get()).map(binding -> binding.currentSpan().targetKey);
    }

    /** @return whether this thread is inside an ignore scope */
    public static boolean isIgnoring() {
        return IGNORE_DEPTH.get()[0] > 0;
    }

    /** Clears all state on this thread. Intended for tests. */
    public static void clear() {
        CURRENT.remove();
        IGNORE_DEPTH.remove();
    }

    private static Binding collectingBinding() {
        Binding binding = CURRENT.get();
        return binding == null || isIgnoring() ? null : binding;
    }

    private static Binding requireBinding() {
        Binding binding = CURRENT.get();
        if (binding == null) {
            throw new IllegalStateException("No PerfLens trace is open on this thread");
        }
        return binding;
    }

    /**
     * Supplier that may throw checked exceptions.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        /**
         * @return the result
         * @throws Exception on failure
         */
        T get() throws Exception;
    }

    /** Marker for tasks already wrapped, so double wrapping is a no-op. */
    private interface JoinedTask {
    }

    /** A thread's view of a trace: the shared state plus this thread's own span stack. */
    private static final class Binding {

        private final TraceState state;
        private final Deque<Span> stack = new ArrayDeque<>();
        private final boolean owner;

        private Binding(TraceState state, Span base, boolean owner) {
            this.state = state;
            this.owner = owner;
            stack.push(base);
        }

        private Span currentSpan() {
            return stack.peek();
        }
    }

    /** Binds the parent trace on the worker thread and adds the task's allocation and CPU to it. */
    private abstract static class Joined implements JoinedTask {

        private final TraceState state;
        private final Span span;

        Joined(TraceState state, Span span) {
            this.state = state;
            this.span = span;
        }

        final <T> T join(ThrowingSupplier<T> work) throws Exception {
            Binding previous = CURRENT.get();
            CURRENT.set(new Binding(state, span, false));
            long allocatedStart = ResourceSampler.allocatedBytes();
            long cpuStart = ResourceSampler.cpuNanos();
            try {
                return work.get();
            }
            finally {
                state.addWorkerUsage(ResourceSampler.delta(allocatedStart, ResourceSampler.allocatedBytes()),
                        ResourceSampler.delta(cpuStart, ResourceSampler.cpuNanos()));
                if (previous == null) {
                    CURRENT.remove();
                }
                else {
                    CURRENT.set(previous);
                }
            }
        }
    }

    private static final class JoinedRunnable extends Joined implements Runnable {

        private final Runnable task;

        JoinedRunnable(TraceState state, Span span, Runnable task) {
            super(state, span);
            this.task = task;
        }

        @Override
        public void run() {
            try {
                join(() -> {
                    task.run();
                    return null;
                });
            }
            catch (RuntimeException | Error ex) {
                throw ex;
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class JoinedCallable<T> extends Joined implements Callable<T> {

        private final Callable<T> task;

        JoinedCallable(TraceState state, Span span, Callable<T> task) {
            super(state, span);
            this.task = task;
        }

        @Override
        public T call() throws Exception {
            return join(task::call);
        }
    }

    private static final class Span {

        private final String id = UUID.randomUUID().toString();
        private final String parentId;
        private final String targetKey;
        private final String label;
        private final TraceBoundary boundary;
        private final Instant startedAt;
        private volatile Instant endedAt;

        private Span(String parentId, String targetKey, String label, TraceBoundary boundary, Instant startedAt) {
            this.parentId = parentId;
            this.targetKey = Objects.requireNonNull(targetKey, "targetKey");
            this.label = label == null ? "" : label;
            this.boundary = Objects.requireNonNull(boundary, "boundary");
            this.startedAt = startedAt;
        }

        private SpanSnapshot snapshot(Instant fallbackEnd) {
            Instant end = endedAt;
            return new SpanSnapshot(id, parentId, targetKey, label, boundary, startedAt, end == null ? fallbackEnd : end);
        }
    }

    /**
     * Everything recorded for one trace. Shared by the owning thread and any
     * joined worker threads, so every mutation is synchronized.
     */
    private static final class TraceState {

        private final String traceId = UUID.randomUUID().toString();
        private final String route;
        private final String httpMethod;
        private final Span root;
        private final Object jfrEvent;
        private final long allocatedStart;
        private final long cpuStart;
        private final long gcCountStart;
        private final long gcMillisStart;
        private final List<Span> spans = new ArrayList<>();
        private final List<SqlEvent> sqlEvents = new ArrayList<>();
        private final List<HibernateEvent> hibernateEvents = new ArrayList<>();
        private final Map<String, Integer> entityLoads = new LinkedHashMap<>();
        private final List<TransactionEvent> transactions = new ArrayList<>();
        private final List<ConnectionEvent> connections = new ArrayList<>();
        private long workerAllocated;
        private long workerCpu;
        private int heldConnections;
        private int droppedEvents;
        private boolean closed;

        private TraceState(String targetKey, String label, TraceBoundary boundary, String route, String httpMethod,
                Instant startedAt) {
            this.root = new Span(null, targetKey, label, boundary, startedAt);
            this.route = route;
            this.httpMethod = httpMethod;
            this.jfrEvent = JfrSupport.beginTrace();
            this.allocatedStart = ResourceSampler.allocatedBytes();
            this.cpuStart = ResourceSampler.cpuNanos();
            this.gcCountStart = ResourceSampler.gcCount();
            this.gcMillisStart = ResourceSampler.gcMillis();
            spans.add(root);
        }

        private synchronized void addSpan(Span span) {
            if (!closed) {
                spans.add(span);
            }
        }

        private synchronized void addSql(String spanId, String sql, int parameterHash, long durationNanos,
                Throwable failure) {
            if (reserveEvent()) {
                SQLException sqlFailure = sqlException(failure);
                sqlEvents.add(new SqlEvent(sqlEvents.size() + 1, spanId, sql, SqlNormalizer.normalize(sql),
                        parameterHash, durationNanos, failure == null, failure == null ? null : failure.getMessage(),
                        sqlFailure == null ? null : sqlFailure.getSQLState(),
                        sqlFailure == null ? 0 : sqlFailure.getErrorCode()));
            }
        }

        private synchronized void addHibernateEvent(String spanId, HibernateEventType eventType, String entityName,
                String collectionRole, String detail) {
            if (closed) {
                return;
            }
            if (eventType == HibernateEventType.ENTITY_LOAD) {
                entityLoads.merge(entityName == null ? "unknown" : entityName, 1, Integer::sum);
            }
            else if (reserveEvent()) {
                hibernateEvents.add(new HibernateEvent(hibernateEvents.size() + 1, spanId, eventType, entityName,
                        collectionRole, detail));
            }
        }

        private synchronized void addTransaction(String spanId, long durationNanos, int statementCount, long sqlNanos,
                boolean committed) {
            if (!closed) {
                transactions.add(new TransactionEvent(transactions.size() + 1, spanId, durationNanos, statementCount,
                        sqlNanos, committed));
            }
        }

        private synchronized boolean addConnection(String spanId, long waitNanos, int poolActive, int poolMax,
                int poolPending) {
            if (closed) {
                return false;
            }
            heldConnections++;
            connections.add(new ConnectionEvent(connections.size() + 1, spanId, waitNanos, heldConnections,
                    poolActive, poolMax, poolPending));
            return true;
        }

        private synchronized void releaseConnection() {
            if (heldConnections > 0) {
                heldConnections--;
            }
        }

        private synchronized void addWorkerUsage(long allocated, long cpu) {
            if (allocated > 0) {
                workerAllocated += allocated;
            }
            if (cpu > 0) {
                workerCpu += cpu;
            }
        }

        private synchronized void discard() {
            closed = true;
        }

        private boolean reserveEvent() {
            if (closed) {
                return false;
            }
            if (sqlEvents.size() + hibernateEvents.size() < maxEvents) {
                return true;
            }
            droppedEvents++;
            return false;
        }

        private synchronized TraceReport close(Integer statusCode, String errorMessage, Instant endedAt) {
            closed = true;
            long allocated = ResourceSampler.delta(allocatedStart, ResourceSampler.allocatedBytes());
            long cpu = ResourceSampler.delta(cpuStart, ResourceSampler.cpuNanos());
            ResourceUsage resources = new ResourceUsage(
                    allocated < 0 ? -1 : allocated + workerAllocated,
                    cpu < 0 ? -1 : cpu + workerCpu,
                    ResourceSampler.gcCount() - gcCountStart,
                    ResourceSampler.gcMillis() - gcMillisStart);
            List<SpanSnapshot> snapshots = spans.stream().map(span -> span.snapshot(endedAt)).toList();
            return new TraceReport(traceId, root.targetKey, root.label, root.boundary, route, httpMethod, statusCode,
                    errorMessage, root.startedAt, endedAt, snapshots, List.copyOf(sqlEvents),
                    List.copyOf(hibernateEvents), Map.copyOf(entityLoads), List.copyOf(transactions),
                    List.copyOf(connections), resources, droppedEvents, List.of());
        }

        private static SQLException sqlException(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof SQLException sql) {
                    return sql;
                }
            }
            return null;
        }
    }
}
