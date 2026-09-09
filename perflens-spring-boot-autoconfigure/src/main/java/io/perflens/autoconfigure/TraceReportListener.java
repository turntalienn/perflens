package io.perflens.autoconfigure;

import io.perflens.core.context.TraceReport;

/**
 * Receives every analyzed trace report.
 *
 * <p>Built-in listeners log a summary and, when storage is enabled, save reports to
 * the database. Declare your own bean of this type to push reports elsewhere
 * (metrics, a message queue, a test collector). With async reporting on, this runs
 * on a single background thread, so keep it quick and do not block.</p>
 */
@FunctionalInterface
public interface TraceReportListener {

    /**
     * @param report completed report including findings
     */
    void onReport(TraceReport report);
}
