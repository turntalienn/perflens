package io.perflens.core.context;

import io.perflens.core.model.FindingSeverity;

/**
 * A problem an analyzer derived from the raw events of a trace.
 *
 * @param type stable identifier such as {@code N_PLUS_ONE}; see the analyzer {@code TYPE} constants
 * @param severity how urgent the finding is
 * @param message one-line explanation and suggested fix
 * @param evidence the numbers and SQL behind the finding
 */
public record TraceFinding(String type, FindingSeverity severity, String message, String evidence) {
}
