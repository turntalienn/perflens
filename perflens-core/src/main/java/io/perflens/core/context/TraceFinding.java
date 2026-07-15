package io.perflens.core.context;

import io.perflens.core.model.FindingSeverity;

/**
 * A human-readable interpretation derived from trace evidence.
 *
 * @param type stable finding type used for filtering
 * @param severity severity assigned by the analyzer
 * @param message report-ready summary
 * @param evidence raw evidence summary used to justify the finding
 */
public record TraceFinding(String type, FindingSeverity severity, String message, String evidence) {
}
