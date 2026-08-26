package io.perflens.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.time.Instant;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerfLensTraceContextTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-31T08:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
        PerfLensTraceContext.setMaxEventsPerTrace(PerfLensTraceContext.DEFAULT_MAX_EVENTS);
    }

    @Test
    void recordsEventsOnlyWhileATraceIsOpen() {
        PerfLensTraceContext.recordSql("select ignored", 0, 1_000_000, null);

        String traceId = startRoot("order-detail");
        PerfLensTraceContext.recordSql("select * from orders where id = ?", 42, 2_500_000, null);
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD, "Order", null, "1");
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD, "Order", null, "2");
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.COLLECTION_INIT, null, "Order.lines", "1");
        PerfLensTraceContext.recordTransaction(3_000_000, 1, 2_500_000, true);
        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.traceId()).isEqualTo(traceId);
        assertThat(report.sqlEvents()).singleElement().satisfies(event -> {
            assertThat(event.parameterHash()).isEqualTo(42);
            assertThat(event.normalizedSql()).isEqualTo("select * from orders where id = ?");
        });
        assertThat(report.hibernateEvents()).singleElement()
                .satisfies(event -> assertThat(event.collectionRole()).isEqualTo("Order.lines"));
        assertThat(report.entityLoads()).containsEntry("Order", 2);
        assertThat(report.transactions()).hasSize(1);
        assertThat(PerfLensTraceContext.isActive()).isFalse();
    }

    @Test
    void ignoreScopeSuppressesCollection() {
        startRoot("order-detail");

        PerfLensTraceContext.runIgnoring(() -> {
            PerfLensTraceContext.recordSql("select * from perflens_control", 0, 2_000_000, null);
            assertThat(PerfLensTraceContext.recordConnectionAcquired(0, -1, -1, -1)).isFalse();
        });

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);
        assertThat(report.sqlEvents()).isEmpty();
        assertThat(report.connections()).isEmpty();
    }

    @Test
    void attributesEventsToTheInnermostSpan() {
        startRoot("order-detail");
        String childId = PerfLensTraceContext.startChildSpan("line-items", "", TraceBoundary.SERVICE_METHOD, clock);
        PerfLensTraceContext.recordSql("select * from line_item where order_id = ?", 1, 1_200_000, null);
        PerfLensTraceContext.closeCurrentSpan(clock);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.spans()).hasSize(2);
        assertThat(report.spans().get(1).parentSpanId()).isEqualTo(report.spans().get(0).spanId());
        assertThat(report.sqlEvents().get(0).spanId()).isEqualTo(childId);
    }

    @Test
    void tracksConnectionsHeldAtTheSameTime() {
        startRoot("nested-tx");
        PerfLensTraceContext.recordConnectionAcquired(1_000, 1, 10, 0);
        PerfLensTraceContext.recordConnectionAcquired(1_000, 2, 10, 0);
        PerfLensTraceContext.recordConnectionReleased();
        PerfLensTraceContext.recordConnectionReleased();
        PerfLensTraceContext.recordConnectionAcquired(1_000, 1, 10, 0);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.connections()).extracting(ConnectionEvent::heldByTrace).containsExactly(1, 2, 1);
    }

    @Test
    void capsStoredEventsAndCountsTheRest() {
        PerfLensTraceContext.setMaxEventsPerTrace(3);
        startRoot("batch");
        for (int i = 0; i < 5; i++) {
            PerfLensTraceContext.recordSql("select " + i, 0, 1, null);
        }

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.sqlEvents()).hasSize(3);
        assertThat(report.droppedEvents()).isEqualTo(2);
    }

    @Test
    void entityLoadsAreCountedWithoutUsingTheEventCap() {
        PerfLensTraceContext.setMaxEventsPerTrace(1);
        startRoot("big-load");
        for (int i = 0; i < 50; i++) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD, "Order", null, null);
        }
        PerfLensTraceContext.recordSql("select * from orders", 0, 1, null);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.totalEntityLoads()).isEqualTo(50);
        assertThat(report.sqlEvents()).hasSize(1);
        assertThat(report.droppedEvents()).isZero();
    }

    @Test
    void failedStatementsKeepSqlStateAndVendorCode() {
        startRoot("deadlock");
        SQLException deadlock = new SQLException("Deadlock found when trying to get lock", "40001", 1213);
        PerfLensTraceContext.recordSql("update plate set status = ?", 1, 5_000_000,
                new RuntimeException("wrapped", deadlock));

        SqlEvent event = PerfLensTraceContext.closeRoot(500, null, clock).sqlEvents().get(0);

        assertThat(event.success()).isFalse();
        assertThat(event.sqlState()).isEqualTo("40001");
        assertThat(event.vendorCode()).isEqualTo(1213);
    }

    @Test
    void measuresAllocationAndCpuOfTheTracedThread() {
        startRoot("allocating");
        byte[][] garbage = new byte[64][];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = new byte[64 * 1024];
        }

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(garbage.length).isEqualTo(64);
        assertThat(report.resources().allocatedBytes()).isGreaterThanOrEqualTo(4L * 1024 * 1024);
        assertThat(report.resources().cpuNanos()).isPositive();
        assertThat(report.resources().gcCount()).isNotNegative();
    }

    @Test
    void wrappedTasksJoinTheTraceOnWorkerThreads() throws Exception {
        startRoot("parallel-pi");
        String spanId = PerfLensTraceContext.startChildSpan("pi-batch", "", TraceBoundary.SERVICE_METHOD, clock);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int plate = i;
                futures.add(executor.submit(PerfLensTraceContext.wrap(() -> {
                    byte[] work = new byte[256 * 1024];
                    PerfLensTraceContext.recordSql("select * from allele where plate = ?", plate, 1_000, null);
                    PerfLensTraceContext.recordConnectionAcquired(1_000, 1, 10, 0);
                    PerfLensTraceContext.recordConnectionReleased();
                    return work.length;
                })));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            Future<Boolean> untraced = executor.submit(PerfLensTraceContext::isActive);
            assertThat(untraced.get()).isFalse();
        }
        finally {
            executor.shutdownNow();
        }
        PerfLensTraceContext.closeCurrentSpan(clock);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.sqlEvents()).hasSize(8).allSatisfy(event -> assertThat(event.spanId()).isEqualTo(spanId));
        assertThat(report.connections()).hasSize(8);
        assertThat(report.resources().allocatedBytes()).isGreaterThanOrEqualTo(8L * 256 * 1024);
    }

    @Test
    void eventsArrivingAfterCloseAreDropped() throws Exception {
        startRoot("late");
        Runnable late = PerfLensTraceContext.wrap(
                () -> PerfLensTraceContext.recordSql("select late", 0, 1, null));
        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        Thread worker = new Thread(late);
        worker.start();
        worker.join();

        assertThat(report.sqlEvents()).isEmpty();
    }

    @Test
    void wrapWithoutATraceReturnsTheTaskUnchanged() {
        Runnable task = () -> { };
        assertThat(PerfLensTraceContext.wrap(task)).isSameAs(task);
    }

    @Test
    void workerThreadsCannotCloseTheTrace() throws Exception {
        startRoot("owner");
        Callable<Object> close = PerfLensTraceContext.wrap(() -> PerfLensTraceContext.closeRoot(200, null, clock));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(close)).failsWithin(java.time.Duration.ofSeconds(5))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withCauseInstanceOf(IllegalStateException.class);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closingWithoutAnOpenTraceFails() {
        assertThatIllegalStateException().isThrownBy(() -> PerfLensTraceContext.closeRoot(200, null, clock));
    }

    @Test
    void isolatesConcurrentTracesByThread() throws Exception {
        Callable<TraceReport> first = () -> {
            startRoot("first");
            PerfLensTraceContext.recordSql("select * from first_table", 0, 1_000_000, null);
            return PerfLensTraceContext.closeRoot(200, null, clock);
        };
        Callable<TraceReport> second = () -> {
            startRoot("second");
            PerfLensTraceContext.recordSql("select * from second_table", 0, 1_000_000, null);
            return PerfLensTraceContext.closeRoot(200, null, clock);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TraceReport> firstReport = executor.submit(first);
            Future<TraceReport> secondReport = executor.submit(second);

            assertThat(firstReport.get().sqlEvents()).singleElement()
                    .satisfies(event -> assertThat(event.sql()).contains("first_table"));
            assertThat(secondReport.get().sqlEvents()).singleElement()
                    .satisfies(event -> assertThat(event.sql()).contains("second_table"));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private String startRoot(String key) {
        return PerfLensTraceContext.startRoot(key, "", TraceBoundary.HTTP_REQUEST, "/" + key, "GET", clock);
    }
}
