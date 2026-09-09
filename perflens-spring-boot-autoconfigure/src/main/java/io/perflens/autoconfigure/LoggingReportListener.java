package io.perflens.autoconfigure;

import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Logs one summary line per trace, followed by one line per finding.
 *
 * <p>Traces with findings log at WARN, clean traces at DEBUG under the
 * {@code io.perflens} logger.</p>
 */
final class LoggingReportListener implements TraceReportListener {

    private static final Log logger = LogFactory.getLog("io.perflens");

    @Override
    public void onReport(TraceReport report) {
        boolean problems = !report.findings().isEmpty();
        if (!problems && !logger.isDebugEnabled()) {
            return;
        }
        StringBuilder message = new StringBuilder("PerfLens [").append(report.targetKey()).append("] ");
        if (report.httpMethod() != null) {
            message.append(report.httpMethod()).append(' ').append(report.route()).append(' ')
                    .append(report.statusCode()).append(' ');
        }
        message.append(String.format("%.1f ms: %d SQL (%.1f ms), %d tx, %d connections, %d findings",
                report.durationMillis(), report.sqlEvents().size(), report.sqlMillis(), report.transactions().size(),
                report.connections().size(), report.findings().size()));
        if (report.resources().allocatedBytes() >= 0) {
            message.append(String.format(", %.1f MB allocated, %.1f ms CPU",
                    report.resources().allocatedBytes() / (1024.0 * 1024), report.resources().cpuNanos() / 1e6));
        }
        if (report.totalEntityLoads() > 0) {
            message.append(", ").append(report.totalEntityLoads()).append(" entities loaded");
        }
        if (report.droppedEvents() > 0) {
            message.append(", ").append(report.droppedEvents()).append(" events dropped");
        }
        for (TraceFinding finding : report.findings()) {
            message.append(System.lineSeparator()).append("  ").append(finding.severity()).append(' ')
                    .append(finding.type()).append(": ").append(finding.message()).append(System.lineSeparator())
                    .append("    ").append(finding.evidence());
        }
        if (problems) {
            logger.warn(message);
        }
        else {
            logger.debug(message);
        }
    }
}
