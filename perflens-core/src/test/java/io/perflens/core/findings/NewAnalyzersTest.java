package io.perflens.core.findings;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.ResourceUsage;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NewAnalyzersTest {

    private final Clock clock = Clock.systemUTC();

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
    }

    @Test
    void classifiesLockErrorsFromEachDatabase() {
        assertThat(LockContentionAnalyzer.classify("40001", 1213, "Deadlock found")).isEqualTo(
                LockContentionAnalyzer.Kind.DEADLOCK);
        assertThat(LockContentionAnalyzer.classify("40P01", 0, "deadlock detected"))
                .isEqualTo(LockContentionAnalyzer.Kind.DEADLOCK);
        assertThat(LockContentionAnalyzer.classify("40001", 1205, "Transaction was deadlocked on lock resources"))
                .isEqualTo(LockContentionAnalyzer.Kind.DEADLOCK);
        assertThat(LockContentionAnalyzer.classify("HY000", 1205, "Lock wait timeout exceeded"))
                .isEqualTo(LockContentionAnalyzer.Kind.LOCK_TIMEOUT);
        assertThat(LockContentionAnalyzer.classify("55P03", 0, "canceling statement due to lock timeout"))
                .isEqualTo(LockContentionAnalyzer.Kind.LOCK_TIMEOUT);
        assertThat(LockContentionAnalyzer.classify("HYT00", 50200, "Timeout trying to lock table"))
                .isEqualTo(LockContentionAnalyzer.Kind.LOCK_TIMEOUT);
        assertThat(LockContentionAnalyzer.classify("40001", 0, "could not serialize access"))
                .isEqualTo(LockContentionAnalyzer.Kind.SERIALIZATION_FAILURE);
        assertThat(LockContentionAnalyzer.classify("23000", 1062, "Duplicate entry")).isNull();
    }

    @Test
    void deadlockIsCritical() {
        PerfLensTraceContext.startRoot("pi-save", "", TraceBoundary.HTTP_REQUEST, "/pi", "POST", clock);
        PerfLensTraceContext.recordSql("insert into report_marker values (?)", 1, 3_000_000,
                new SQLException("Deadlock found when trying to get lock", "40001", 1213));

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(LockContentionAnalyzer.TYPE);
            assertThat(finding.severity()).isEqualTo(FindingSeverity.CRITICAL);
            assertThat(finding.evidence()).contains("1213", "insert into report_marker");
        });
    }

    @Test
    void largeEntityLoadIsReportedWithTheTopEntities() {
        PerfLensTraceContext.startRoot("case-load", "", TraceBoundary.HTTP_REQUEST, "/cases", "GET", clock);
        for (int i = 0; i < 1_200; i++) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD,
                    i % 4 == 0 ? "Sample" : "Allele", null, null);
        }

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PersistenceContextAnalyzer.TYPE);
            assertThat(finding.message()).contains("1200 entities");
            assertThat(finding.evidence()).startsWith("Allele x900, Sample x300");
        });
    }

    @Test
    void highAllocationAndGcPressureAreReported() {
        TraceReport report = reportWith(new ResourceUsage(700L * 1024 * 1024, 900_000_000L, 3, 240));
        MemoryAnalyzer analyzer = new MemoryAnalyzer(64L * 1024 * 1024, Duration.ofMillis(100));

        List<TraceFinding> findings = analyzer.analyze(report);

        assertThat(findings).extracting(TraceFinding::type)
                .containsExactly(MemoryAnalyzer.HIGH_ALLOCATION, MemoryAnalyzer.GC_PRESSURE);
        assertThat(findings.get(0).severity()).isEqualTo(FindingSeverity.CRITICAL);
        assertThat(findings.get(0).message()).contains("700.0 MB");
        assertThat(findings.get(1).message()).contains("3 GC pauses totalling 240 ms");
    }

    @Test
    void unknownResourcesProduceNoFindings() {
        MemoryAnalyzer analyzer = new MemoryAnalyzer(1, Duration.ofMillis(1));
        assertThat(analyzer.analyze(reportWith(ResourceUsage.UNKNOWN))).isEmpty();
    }

    private TraceReport reportWith(ResourceUsage usage) {
        PerfLensTraceContext.startRoot("r", "", TraceBoundary.HTTP_REQUEST, "/r", "GET", clock);
        TraceReport base = PerfLensTraceContext.closeRoot(200, null, clock);
        return new TraceReport(base.traceId(), base.targetKey(), base.label(), base.boundary(), base.route(),
                base.httpMethod(), base.statusCode(), base.errorMessage(), base.startedAt(), base.endedAt(),
                base.spans(), base.sqlEvents(), base.hibernateEvents(), base.entityLoads(), base.transactions(),
                base.connections(), usage, 0, List.of());
    }

    private List<TraceFinding> analyze() {
        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);
        return TraceAnalyzers.analyze(report, TraceAnalyzers.defaults(AnalysisThresholds.defaults())).findings();
    }
}
