package io.perflens.core.findings;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceAnalyzersTest {

    private static final long MS = 1_000_000;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-31T08:00:00Z"), ZoneOffset.UTC);
    private final List<TraceAnalyzer> analyzers = TraceAnalyzers.defaults(AnalysisThresholds.defaults());

    @BeforeEach
    void openTrace() {
        PerfLensTraceContext.startRoot("t", "", TraceBoundary.HTTP_REQUEST, "/t", "GET", clock);
    }

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
    }

    @Test
    void cleanTraceHasNoFindings() {
        PerfLensTraceContext.recordSql("select * from author", 0, MS, null);
        PerfLensTraceContext.recordConnectionAcquired(MS / 10, 1, 10, 0);
        PerfLensTraceContext.recordTransaction(5 * MS, 1, MS, true);

        assertThat(analyze()).isEmpty();
    }

    @Test
    void sameShapeWithDifferentParametersIsNPlusOne() {
        for (int id = 1; id <= 5; id++) {
            PerfLensTraceContext.recordSql("select * from book where author_id = ?", id, MS, null);
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.COLLECTION_INIT, null, "Author.books", "");
        }

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(NPlusOneAnalyzer.TYPE);
            assertThat(finding.evidence()).contains("5 executions", "Author.books x5");
        });
    }

    @Test
    void identicalParametersAreDuplicatesNotNPlusOne() {
        for (int i = 0; i < 5; i++) {
            PerfLensTraceContext.recordSql("select * from settings where name = ?", 7, MS, null);
        }

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(DuplicateQueryAnalyzer.TYPE);
            assertThat(finding.message()).contains("5 times");
        });
    }

    @Test
    void sequenceFetchesAreNotDuplicates() {
        PerfLensTraceContext.recordSql("select nextval('book_seq')", 0, MS, null);
        PerfLensTraceContext.recordSql("select nextval('book_seq')", 0, MS, null);

        assertThat(analyze()).isEmpty();
    }

    @Test
    void slowQueryIsReported() {
        PerfLensTraceContext.recordSql("select * from big_table", 0, 300 * MS, null);

        assertThat(analyze()).extracting(TraceFinding::type).containsExactly(SlowQueryAnalyzer.TYPE);
    }

    @Test
    void slowTransactionPointsAtWorkOutsideSql() {
        PerfLensTraceContext.recordTransaction(800 * MS, 2, 10 * MS, true);

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(SlowTransactionAnalyzer.TYPE);
            assertThat(finding.message()).contains("Move remote calls");
            assertThat(finding.evidence()).contains("committed", "2 statements");
        });
    }

    @Test
    void poolWaitAndQueuedThreadsAreCritical() {
        PerfLensTraceContext.recordConnectionAcquired(120 * MS, 10, 10, 3);

        assertThat(analyze()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(ConnectionPoolAnalyzer.TYPE);
            assertThat(finding.severity()).isEqualTo(FindingSeverity.CRITICAL);
            assertThat(finding.evidence()).contains("pool active 10/10", "waiting threads 3");
        });
    }

    @Test
    void briefQueueingWithoutARealWaitIsIgnored() {
        PerfLensTraceContext.recordConnectionAcquired(MS / 10, 3, 10, 2);

        assertThat(analyze()).isEmpty();
    }

    @Test
    void holdingTwoConnectionsAtOnceIsReported() {
        PerfLensTraceContext.recordConnectionAcquired(MS / 10, 1, 10, 0);
        PerfLensTraceContext.recordConnectionAcquired(MS / 10, 2, 10, 0);

        assertThat(analyze()).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("held 2 connections"));
    }

    @Test
    void failingAnalyzerBecomesAnInfoFinding() {
        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);
        TraceAnalyzer broken = ignored -> {
            throw new IllegalStateException("boom");
        };

        assertThat(TraceAnalyzers.analyze(report, List.of(broken)).findings()).singleElement()
                .satisfies(finding -> assertThat(finding.type()).isEqualTo("ANALYZER_FAILED"));
    }

    private List<TraceFinding> analyze() {
        return TraceAnalyzers.analyze(PerfLensTraceContext.closeRoot(200, null, clock), analyzers).findings();
    }
}
