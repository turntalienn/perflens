package io.perflens.core.findings;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import io.perflens.core.model.TraceBoundary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NPlusOneFindingAnalyzerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-31T08:00:00Z"), ZoneOffset.UTC);
    private final NPlusOneFindingAnalyzer analyzer = new NPlusOneFindingAnalyzer();

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
    }

    @Test
    void flagsRepeatedNormalizedSelectsAsHighConfidenceNPlusOneCandidate() {
        PerfLensTraceContext.startRoot("order-detail", "Order detail", TraceBoundary.HTTP_REQUEST,
                "/orders/{id}", "GET", clock);

        PerfLensTraceContext.recordSql("select * from item_attribute where item_id = 1", 1_000_000, true, null);
        PerfLensTraceContext.recordSql("select * from item_attribute where item_id = 2", 1_100_000, true, null);
        PerfLensTraceContext.recordSql("select * from item_attribute where item_id = 3", 1_200_000, true, null);
        PerfLensTraceContext.recordSql("select * from item_attribute where item_id = 4", 1_300_000, true, null);

        TraceReport report = PerfLensTraceContext.closeRoot(200, null, clock);

        assertThat(analyzer.analyze(report)).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo("N_PLUS_ONE_CANDIDATE");
            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
            assertThat(finding.evidence()).contains("4 repeated executions");
        });
    }
}
