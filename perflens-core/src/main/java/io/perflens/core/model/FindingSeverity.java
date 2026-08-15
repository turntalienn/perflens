package io.perflens.core.model;

/**
 * Severity of a finding, in ascending order.
 */
public enum FindingSeverity {

    /** Worth knowing; not necessarily a problem. */
    INFO,

    /** Likely a problem worth fixing. */
    WARNING,

    /** Large enough to hurt latency or stability on its own. */
    CRITICAL
}
