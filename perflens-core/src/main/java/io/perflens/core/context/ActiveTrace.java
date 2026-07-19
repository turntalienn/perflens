package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the root trace currently bound to a request thread.
 *
 * <p>This object is intentionally lightweight. Detailed evidence is recorded in
 * the associated {@link PerfLensTraceContext}; callers mostly use it to expose
 * the trace id for logging or correlation.</p>
 */
public final class ActiveTrace {

    private final String traceId;
    private final String targetKey;
    private final String label;
    private final TraceBoundary boundary;
    private final String route;
    private final String httpMethod;
    private final Instant startedAt;

    ActiveTrace(String targetKey, String label, TraceBoundary boundary, String route, String httpMethod,
            Instant startedAt) {
        this.traceId = UUID.randomUUID().toString();
        this.targetKey = Objects.requireNonNull(targetKey, "targetKey");
        this.label = label == null ? "" : label;
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.route = route;
        this.httpMethod = httpMethod;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * @return unique identifier for the complete trace report
     */
    public String traceId() {
        return traceId;
    }

    /**
     * @return root annotation key that opened the trace
     */
    public String targetKey() {
        return targetKey;
    }

    /**
     * @return root trace display label
     */
    public String label() {
        return label;
    }

    /**
     * @return boundary type that opened the root trace
     */
    public TraceBoundary boundary() {
        return boundary;
    }

    /**
     * @return HTTP route pattern when the root trace is a controller request
     */
    public String route() {
        return route;
    }

    /**
     * @return HTTP method when the root trace is a controller request
     */
    public String httpMethod() {
        return httpMethod;
    }

    /**
     * @return instant when the trace opened
     */
    public Instant startedAt() {
        return startedAt;
    }
}
