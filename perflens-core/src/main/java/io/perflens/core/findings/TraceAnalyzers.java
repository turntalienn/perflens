package io.perflens.core.findings;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the default analyzer set and runs analyzers over a report.
 */
public final class TraceAnalyzers {

    private TraceAnalyzers() {
    }

    /**
     * @param thresholds thresholds for the built-in analyzers
     * @return the N+1, duplicate query, slow query, slow transaction, connection pool,
     *         lock contention, memory and persistence context analyzers
     */
    public static List<TraceAnalyzer> defaults(AnalysisThresholds thresholds) {
        return List.of(
                new NPlusOneAnalyzer(thresholds.nPlusOneExecutions()),
                new DuplicateQueryAnalyzer(),
                new SlowQueryAnalyzer(thresholds.slowQuery()),
                new SlowTransactionAnalyzer(thresholds.slowTransaction()),
                new ConnectionPoolAnalyzer(thresholds.connectionWait()),
                new LockContentionAnalyzer(),
                new MemoryAnalyzer(thresholds.allocationBytes(), thresholds.gcTime()),
                new PersistenceContextAnalyzer(thresholds.entityLoads()));
    }

    /**
     * Runs every analyzer and attaches the findings, most severe first. An analyzer
     * that throws is reported as an {@code ANALYZER_FAILED} finding instead of
     * failing the whole report.
     *
     * @param report report to analyze
     * @param analyzers analyzers to run
     * @return copy of {@code report} with findings
     */
    public static TraceReport analyze(TraceReport report, List<? extends TraceAnalyzer> analyzers) {
        List<TraceFinding> findings = new ArrayList<>();
        for (TraceAnalyzer analyzer : analyzers) {
            try {
                findings.addAll(analyzer.analyze(report));
            }
            catch (RuntimeException ex) {
                findings.add(new TraceFinding("ANALYZER_FAILED", FindingSeverity.INFO,
                        analyzer.getClass().getSimpleName() + " failed: " + ex, ""));
            }
        }
        findings.sort(Comparator.comparing(TraceFinding::severity).reversed());
        return report.withFindings(findings);
    }

    static String millis(long nanos) {
        return String.format("%.1f ms", nanos / 1_000_000.0);
    }

    static String bytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / (1024.0 * 1024))
                : String.format("%.1f KB", bytes / 1024.0);
    }
}
