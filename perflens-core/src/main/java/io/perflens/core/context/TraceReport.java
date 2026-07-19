package io.perflens.core.context;

import io.perflens.core.model.TraceBoundary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable report produced when a root trace closes.
 *
 * <p>A report is the handoff point between runtime collectors and persistence or
 * rendering. Collectors can stay simple and fast while storage/viewer modules
 * work from immutable data.</p>
 *
 * @param traceId unique trace id
 * @param targetKey root annotation key
 * @param label root display label
 * @param boundary root boundary type
 * @param route HTTP route pattern when available
 * @param httpMethod HTTP method when available
 * @param statusCode HTTP status code when available
 * @param errorMessage error message when the traced call failed
 * @param startedAt trace start instant
 * @param endedAt trace end instant
 * @param spans immutable span list
 * @param sqlEvents immutable SQL evidence list
 * @param hibernateEvents immutable Hibernate evidence list
 * @param findings immutable finding list
 */
public record TraceReport(
        String traceId,
        String targetKey,
        String label,
        TraceBoundary boundary,
        String route,
        String httpMethod,
        Integer statusCode,
        String errorMessage,
        Instant startedAt,
        Instant endedAt,
        List<SpanSnapshot> spans,
        List<SqlEvent> sqlEvents,
        List<HibernateEvent> hibernateEvents,
        List<TraceFinding> findings) {

    /**
     * @return elapsed trace duration in milliseconds
     */
    public double durationMillis() {
        return Duration.between(startedAt, endedAt).toNanos() / 1_000_000.0;
    }

    /**
     * @return optional error message associated with a failed trace
     */
    public Optional<String> error() {
        return Optional.ofNullable(errorMessage);
    }
}
