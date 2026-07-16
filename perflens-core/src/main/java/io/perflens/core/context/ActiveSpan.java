package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mutable representation of a currently executing span inside an active trace.
 *
 * <p>Application code normally does not create spans directly. Spring MVC and
 * AOP adapters open spans when they see {@code @PerfLensTrace}. The class is
 * public so advanced integrations can inspect the current span id for
 * correlation, but mutation is intentionally package-private.</p>
 */
public final class ActiveSpan {

    private final String spanId;
    private final String parentSpanId;
    private final String targetKey;
    private final String label;
    private final TraceBoundary boundary;
    private final Instant startedAt;
    private Instant endedAt;

    ActiveSpan(String parentSpanId, String targetKey, String label, TraceBoundary boundary, Instant startedAt) {
        this.spanId = UUID.randomUUID().toString();
        this.parentSpanId = parentSpanId;
        this.targetKey = Objects.requireNonNull(targetKey, "targetKey");
        this.label = label == null ? "" : label;
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * @return unique span identifier within a trace report
     */
    public String spanId() {
        return spanId;
    }

    /**
     * @return parent span id when this span was opened inside another span
     */
    public Optional<String> parentSpanId() {
        return Optional.ofNullable(parentSpanId);
    }

    /**
     * @return operational annotation key represented by this span
     */
    public String targetKey() {
        return targetKey;
    }

    /**
     * @return human-readable label, or an empty string when none was supplied
     */
    public String label() {
        return label;
    }

    /**
     * @return application boundary type represented by this span
     */
    public TraceBoundary boundary() {
        return boundary;
    }

    /**
     * @return instant when the span opened
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * @return instant when the span closed, if it has closed
     */
    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }

    void close(Instant endedAt) {
        this.endedAt = Objects.requireNonNull(endedAt, "endedAt");
    }

    SpanSnapshot snapshot(Instant fallbackEnd) {
        Instant effectiveEnd = endedAt == null ? fallbackEnd : endedAt;
        return new SpanSnapshot(spanId, Optional.ofNullable(parentSpanId), targetKey, label, boundary, startedAt,
                effectiveEnd);
    }
}
