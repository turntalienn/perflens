package io.perflens.core.findings;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import java.util.List;

/**
 * Turns the raw events of a completed trace into findings.
 *
 * <p>Analyzers run off the request thread when async reporting is on, and must be
 * stateless and thread-safe. In Spring Boot, any bean of this type is picked up
 * in addition to the built-in analyzers.</p>
 */
@FunctionalInterface
public interface TraceAnalyzer {

    /**
     * @param report completed trace, without findings
     * @return findings for this trace, empty when nothing is wrong
     */
    List<TraceFinding> analyze(TraceReport report);
}
