package io.perflens.core.findings;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Flags traces in which Hibernate loaded a large number of entities.
 *
 * <p>Every loaded entity stays in the persistence context until the session ends:
 * it holds memory, and on every flush Hibernate dirty-checks it against a snapshot.
 * Thousands of managed entities make flushes slow and drive allocation and GC.</p>
 */
public final class PersistenceContextAnalyzer implements TraceAnalyzer {

    /** Finding type. */
    public static final String TYPE = "LARGE_PERSISTENCE_CONTEXT";

    private final int threshold;

    /**
     * @param threshold entities loaded in one trace that count as large
     */
    public PersistenceContextAnalyzer(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public List<TraceFinding> analyze(TraceReport report) {
        int total = report.totalEntityLoads();
        if (total < threshold) {
            return List.of();
        }
        String byEntity = report.entityLoads().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
        FindingSeverity severity = total >= threshold * 10 ? FindingSeverity.CRITICAL : FindingSeverity.WARNING;
        return List.of(new TraceFinding(TYPE, severity,
                "Hibernate loaded " + total + " entities in one request. Each one is kept and dirty-checked until "
                        + "the session ends. Page the query, use a DTO projection, or mark it read-only.",
                byEntity));
    }
}
