package io.perflens.core.findings;

import io.perflens.core.context.ResourceUsage;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Flags requests that allocate a lot of heap, and requests that overlapped long
 * GC pauses.
 *
 * <p>Allocation is measured on the request thread and joined worker threads, so
 * it points at the request that creates the garbage. GC time is JVM-wide: it says
 * a pause hit this request, not that this request caused it. Read both together.</p>
 */
public final class MemoryAnalyzer implements TraceAnalyzer {

    /** Finding type for heavy allocation. */
    public static final String HIGH_ALLOCATION = "HIGH_ALLOCATION";

    /** Finding type for GC pauses during the request. */
    public static final String GC_PRESSURE = "GC_PRESSURE";

    private final long allocationThreshold;
    private final long gcThresholdMillis;

    /**
     * @param allocationThreshold allocated bytes that count as high
     * @param gcThreshold GC time during a trace that counts as pressure
     */
    public MemoryAnalyzer(long allocationThreshold, Duration gcThreshold) {
        this.allocationThreshold = allocationThreshold;
        this.gcThresholdMillis = gcThreshold.toMillis();
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        ResourceUsage usage = report.resources();
        List<TraceFinding> findings = new ArrayList<>();
        if (usage.allocatedBytes() >= allocationThreshold) {
            FindingSeverity severity = usage.allocatedBytes() >= allocationThreshold * 10 ? FindingSeverity.CRITICAL
                    : FindingSeverity.WARNING;
            findings.add(new TraceFinding(HIGH_ALLOCATION, severity,
                    "Request allocated " + TraceAnalyzers.bytes(usage.allocatedBytes()) + " of heap. "
                            + hint(report),
                    evidence(report)));
        }
        if (usage.gcMillis() >= gcThresholdMillis && usage.gcMillis() > 0) {
            findings.add(new TraceFinding(GC_PRESSURE, FindingSeverity.WARNING,
                    usage.gcCount() + " GC pauses totalling " + usage.gcMillis() + " ms ran during this request. "
                            + "GC is JVM-wide, so check which requests allocate the most around this time.",
                    evidence(report)));
        }
        return findings;
    }

    private static String hint(TraceReport report) {
        if (report.totalEntityLoads() >= 1_000) {
            return "Most of it is likely the " + report.totalEntityLoads() + " entities Hibernate loaded; "
                    + "page the query or select a DTO projection.";
        }
        return "Look for large collections, result sets read fully into memory, or big JSON/PDF buffers.";
    }

    private static String evidence(TraceReport report) {
        ResourceUsage usage = report.resources();
        String loads = report.entityLoads().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
        return "allocated " + (usage.allocatedBytes() < 0 ? "n/a" : TraceAnalyzers.bytes(usage.allocatedBytes()))
                + ", CPU " + (usage.cpuNanos() < 0 ? "n/a" : TraceAnalyzers.millis(usage.cpuNanos()))
                + ", wall " + String.format("%.1f ms", report.durationMillis())
                + ", GC " + usage.gcCount() + " pauses / " + usage.gcMillis() + " ms"
                + ", entities loaded " + report.totalEntityLoads() + (loads.isEmpty() ? "" : " (" + loads + ")");
    }
}
