package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Immutable span data stored in a completed trace report.
 *
 * @param spanId unique span id
 * @param parentSpanId optional parent span id for nested annotations
 * @param targetKey annotation key
 * @param label display label
 * @param boundary boundary type
 * @param startedAt start instant
 * @param endedAt end instant
 */
public record SpanSnapshot(
        String spanId,
        Optional<String> parentSpanId,
        String targetKey,
        String label,
        TraceBoundary boundary,
        Instant startedAt,
        Instant endedAt) {

    /**
     * @return elapsed span duration in milliseconds
     */
    public double durationMillis() {
        return Duration.between(startedAt, endedAt).toNanos() / 1_000_000.0;
    }
}
