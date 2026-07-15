package io.perflens.core.model;

/**
 * Severity level for a PerfLens finding.
 *
 * <p>Findings are intentionally separate from raw evidence. A SQL event or
 * Hibernate event is something PerfLens observed. A finding is an interpretation
 * of those observations, such as a likely N+1 query pattern.</p>
 */
public enum FindingSeverity {

    /**
     * Informational note that helps explain the trace but does not imply a bug.
     */
    INFO,

    /**
     * A suspicious pattern worth investigating.
     */
    WARNING,

    /**
     * A high-impact pattern that likely needs code or query changes.
     */
    CRITICAL
}
