package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Duration;
import java.time.Instant;

/**
 * A closed span inside a trace report.
 *
 * @param spanId span id, unique within the report
 * @param parentSpanId parent span id, or {@code null} for the root span
 * @param targetKey {@code @PerfLensTrace} key of the boundary
 * @param label display label
 * @param boundary boundary kind
 * @param startedAt start time
 * @param endedAt end time
 */
public record SpanSnapshot(
        String spanId,
        String parentSpanId,
        String targetKey,
        String label,
        TraceBoundary boundary,
        Instant startedAt,
        Instant endedAt) {

    /** @return span duration in milliseconds */
    public double durationMillis() {
        return Duration.between(startedAt, endedAt).toNanos() / 1_000_000.0;
    }
}
