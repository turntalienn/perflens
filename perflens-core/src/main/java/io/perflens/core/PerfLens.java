package io.perflens.core;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.findings.AnalysisThresholds;
import io.perflens.core.findings.TraceAnalyzer;
import io.perflens.core.findings.TraceAnalyzers;
import io.perflens.core.jfr.JfrSupport;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.util.List;

/**
 * Programmatic tracing, mainly for tests.
 *
 * <p>Runs a block of code under a trace and returns the analyzed report, without
 * annotations, control rows or storage. Statements are only captured when the
 * DataSource is instrumented, which the Spring Boot starter does automatically.</p>
 *
 * <pre>{@code
 * TraceReport report = PerfLens.capture("order-list", () -> orderService.listOrders()).report();
 * assertThat(report.findingsOfType(NPlusOneAnalyzer.TYPE)).isEmpty();
 * assertThat(report.sqlEvents()).hasSizeLessThanOrEqualTo(3);
 * }</pre>
 */
public final class PerfLens {

    private static final List<TraceAnalyzer> DEFAULT_ANALYZERS = TraceAnalyzers.defaults(AnalysisThresholds.defaults());

    private PerfLens() {
    }

    /**
     * Runs {@code work} under a new trace analyzed with the default thresholds.
     *
     * @param key key recorded on the trace
     * @param work code to run; exceptions propagate unchanged
     * @param <T> result type
     * @return the result together with the analyzed report
     * @throws IllegalStateException when a trace is already open on this thread
     */
    public static <T> Captured<T> capture(String key, PerfLensTraceContext.ThrowingSupplier<T> work) throws Exception {
        return capture(key, DEFAULT_ANALYZERS, work);
    }

    /**
     * Runs {@code work} under a new trace analyzed with the given analyzers.
     *
     * @param key key recorded on the trace
     * @param analyzers analyzers to run on the report
     * @param work code to run; exceptions propagate unchanged
     * @param <T> result type
     * @return the result together with the analyzed report
     * @throws IllegalStateException when a trace is already open on this thread
     */
    public static <T> Captured<T> capture(String key, List<? extends TraceAnalyzer> analyzers,
            PerfLensTraceContext.ThrowingSupplier<T> work) throws Exception {
        if (PerfLensTraceContext.isActive()) {
            throw new IllegalStateException("A PerfLens trace is already open on this thread");
        }
        Clock clock = Clock.systemUTC();
        PerfLensTraceContext.startRoot(key, key, TraceBoundary.SERVICE_METHOD, null, null, clock);
        T value;
        try {
            value = work.get();
        }
        catch (Exception | Error ex) {
            PerfLensTraceContext.discard();
            throw ex;
        }
        TraceReport report = TraceAnalyzers.analyze(PerfLensTraceContext.closeRoot(null, null, clock), analyzers);
        JfrSupport.emitFindings(report);
        return new Captured<>(value, report);
    }

    /**
     * Result of {@link #capture}.
     *
     * @param value what the captured code returned
     * @param report analyzed trace report
     * @param <T> result type
     */
    public record Captured<T>(T value, TraceReport report) {
    }
}
