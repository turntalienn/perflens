package io.perflens.core.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.PerfLens;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.TraceBoundary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrSupportTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void reset() {
        JfrSupport.setEnabled(true);
        PerfLensTraceContext.clear();
    }

    @Test
    void tracesSqlAndFindingsAppearInARecording() throws Exception {
        Path file = tempDir.resolve("perflens.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("io.perflens.Trace");
            recording.enable("io.perflens.Sql").withStackTrace();
            recording.enable("io.perflens.Finding");
            recording.start();

            PerfLens.capture("orders", () -> {
                for (int id = 1; id <= 4; id++) {
                    Object sql = JfrSupport.beginSql();
                    PerfLensTraceContext.recordSql("select * from line where order_id = ?", id, 1_000, null);
                    JfrSupport.commitSql(sql, PerfLensTraceContext.currentTraceId().orElseThrow(),
                            "select * from line where order_id = ?", true);
                }
                return null;
            });

            recording.stop();
            recording.dump(file);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        assertThat(events).filteredOn(e -> e.getEventType().getName().equals("io.perflens.Trace")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getString("targetKey")).isEqualTo("orders");
                    assertThat(e.getInt("sqlCount")).isEqualTo(4);
                    assertThat(e.getLong("allocatedBytes")).isNotNegative();
                });
        assertThat(events).filteredOn(e -> e.getEventType().getName().equals("io.perflens.Sql")).hasSize(4)
                .allSatisfy(e -> assertThat(e.getStackTrace()).isNotNull());
        assertThat(events).filteredOn(e -> e.getEventType().getName().equals("io.perflens.Finding")).singleElement()
                .satisfies(e -> assertThat(e.getString("findingType")).isEqualTo("N_PLUS_ONE"));
        Files.deleteIfExists(file);
    }

    @Test
    void disabledSupportEmitsNothing() {
        JfrSupport.setEnabled(false);
        assertThat(JfrSupport.beginSql()).isNull();
        PerfLensTraceContext.startRoot("x", "", TraceBoundary.HTTP_REQUEST, "/x", "GET", Clock.systemUTC());
        PerfLensTraceContext.closeRoot(200, null, Clock.systemUTC());
    }
}
