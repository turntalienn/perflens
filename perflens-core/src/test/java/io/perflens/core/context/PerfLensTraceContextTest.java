package io.perflens.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerfLensTraceContextTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-31T08:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
    }

    @Test
    void recordsSqlAndHibernateEventsOnlyInsideAnActiveTrace() {
        PerfLensTraceContext.recordSql("select ignored", 1_000_000, true, null);

        ActiveTrace trace = PerfLensTraceContext.startRoot("order-detail", "Order detail", TraceBoundary.HTTP_REQUEST,
                "/orders/{id}", "GET", clock);
        PerfLensTraceContext.recordSql("select * from orders where id = ?", 2_500_000, true, null);
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD, "Order", null, null);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.traceId()).isEqualTo(trace.traceId());
        assertThat(report.sqlEvents()).hasSize(1);
        assertThat(report.hibernateEvents()).hasSize(1);
        assertThat(report.route()).isEqualTo("/orders/{id}");
        assertThat(PerfLensTraceContext.currentTrace()).isEmpty();
    }

    @Test
    void internalIgnoreScopeSuppressesCollectorEvents() {
        PerfLensTraceContext.startRoot("order-detail", "Order detail", TraceBoundary.HTTP_REQUEST,
                "/orders/{id}", "GET", clock);

        PerfLensTraceContext.runIgnoring(() ->
                PerfLensTraceContext.recordSql("select * from perflens_control", 2_000_000, true, null));

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.sqlEvents()).isEmpty();
    }

    @Test
    void childSpansAreAttachedToTheOpenTrace() {
        PerfLensTraceContext.startRoot("order-detail", "Order detail", TraceBoundary.HTTP_REQUEST,
                "/orders/{id}", "GET", clock);
        ActiveSpan child = PerfLensTraceContext.startChildSpan("line-items", "Line items", TraceBoundary.SERVICE_METHOD,
                clock);
        PerfLensTraceContext.recordSql("select * from line_item where order_id = ?", 1_200_000, true, null);
        PerfLensTraceContext.closeCurrentSpan(clock);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(report.spans()).anySatisfy(span -> {
            assertThat(span.spanId()).isEqualTo(child.spanId());
            assertThat(span.parentSpanId()).isPresent();
        });
        assertThat(report.sqlEvents()).singleElement()
                .satisfies(event -> assertThat(event.spanId()).isEqualTo(child.spanId()));
    }

    @Test
    void isolatesConcurrentTracesByThread() throws Exception {
        Callable<TraceReport> first = () -> {
            PerfLensTraceContext.startRoot("first", "First", TraceBoundary.HTTP_REQUEST, "/first", "GET", clock);
            PerfLensTraceContext.recordSql("select * from first_table", 1_000_000, true, null);
            return PerfLensTraceContext.closeRoot(200, null, clock);
        };
        Callable<TraceReport> second = () -> {
            PerfLensTraceContext.startRoot("second", "Second", TraceBoundary.HTTP_REQUEST, "/second", "GET", clock);
            PerfLensTraceContext.recordSql("select * from second_table", 1_000_000, true, null);
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
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
