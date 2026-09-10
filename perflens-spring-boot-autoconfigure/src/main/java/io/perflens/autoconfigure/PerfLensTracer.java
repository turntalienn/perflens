package io.perflens.autoconfigure;

import io.perflens.core.annotation.PerfLensTrace;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.findings.TraceAnalyzer;
import io.perflens.core.findings.TraceAnalyzers;
import io.perflens.core.jfr.JfrSupport;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Opens and closes root traces for the boundary adapters, then analyzes each
 * report and hands it to the {@link TraceReportListener}s.
 *
 * <p>With async reporting, analysis and listeners run on one daemon thread fed by
 * a bounded queue. When the queue is full, reports are dropped rather than slowing
 * the application down. Pending reports are flushed on shutdown.</p>
 */
public final class PerfLensTracer implements DisposableBean {

    private static final Log logger = LogFactory.getLog(PerfLensTracer.class);
    private static final int QUEUE_CAPACITY = 1_000;

    private final TraceControl control;
    private final List<TraceAnalyzer> analyzers;
    private final List<TraceReportListener> listeners;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private volatile boolean dropWarned;

    /**
     * @param control decides which keys are traced
     * @param analyzers analyzers run on every report
     * @param listeners receivers of analyzed reports
     * @param clock time source
     * @param async publish on a background thread
     */
    public PerfLensTracer(TraceControl control, List<? extends TraceAnalyzer> analyzers,
            List<? extends TraceReportListener> listeners, Clock clock, boolean async) {
        this.control = control;
        this.analyzers = List.copyOf(analyzers);
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.executor = async && !listeners.isEmpty() ? newExecutor() : null;
    }

    /**
     * Opens a root trace for an annotated boundary if its key is enabled and no
     * trace is open on this thread yet.
     *
     * @param annotation the boundary's annotation
     * @param boundary boundary kind
     * @param route MVC route pattern, or {@code null}
     * @param httpMethod HTTP method, or {@code null}
     * @return whether a trace was opened; only then call {@link #finishRoot}
     */
    public boolean startRoot(PerfLensTrace annotation, TraceBoundary boundary, String route, String httpMethod) {
        if (PerfLensTraceContext.isActive() || !control.isEnabled(annotation.value())) {
            return false;
        }
        PerfLensTraceContext.startRoot(annotation.value(), labelOf(annotation), boundary, route, httpMethod, clock);
        return true;
    }

    /**
     * Opens a root trace without consulting {@link TraceControl}, used by
     * {@code perflens.trace-all-requests}.
     *
     * @param key trace key
     * @param route MVC route pattern
     * @param httpMethod HTTP method
     * @return whether a trace was opened
     */
    public boolean startRequest(String key, String route, String httpMethod) {
        if (PerfLensTraceContext.isActive()) {
            return false;
        }
        PerfLensTraceContext.startRoot(key, key, TraceBoundary.HTTP_REQUEST, route, httpMethod, clock);
        return true;
    }

    /**
     * Opens a child span for a nested annotated method.
     *
     * @param annotation the method's annotation
     */
    public void startSpan(PerfLensTrace annotation) {
        PerfLensTraceContext.startChildSpan(annotation.value(), labelOf(annotation), TraceBoundary.SERVICE_METHOD,
                clock);
    }

    /** Closes the innermost child span. */
    public void finishSpan() {
        PerfLensTraceContext.closeCurrentSpan(clock);
    }

    /**
     * Closes the root trace on this thread and publishes its report. Does nothing
     * when no trace is open.
     *
     * @param statusCode HTTP status, or {@code null}
     * @param error failure, or {@code null}
     */
    public void finishRoot(Integer statusCode, Throwable error) {
        if (!PerfLensTraceContext.isActive()) {
            return;
        }
        TraceReport report = PerfLensTraceContext.closeRoot(statusCode,
                error == null ? null : error.getClass().getName(), clock);
        if (listeners.isEmpty()) {
            return;
        }
        if (executor == null) {
            deliver(report);
            return;
        }
        try {
            executor.execute(() -> deliver(report));
        }
        catch (RejectedExecutionException ex) {
            if (!dropWarned) {
                dropWarned = true;
                logger.warn("PerfLens report queue is full, dropping reports until it drains");
            }
        }
    }

    private void deliver(TraceReport rawReport) {
        TraceReport report = TraceAnalyzers.analyze(rawReport, analyzers);
        JfrSupport.emitFindings(report);
        for (TraceReportListener listener : listeners) {
            try {
                listener.onReport(report);
            }
            catch (RuntimeException ex) {
                logger.warn("PerfLens listener " + listener.getClass().getName() + " failed", ex);
            }
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static String labelOf(PerfLensTrace annotation) {
        return annotation.label().isBlank() ? annotation.value() : annotation.label();
    }

    private static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "perflens-reporter");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
