package io.perflens.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTML report viewer for saved PerfLens traces.
 */
@RestController
@RequestMapping("/perflens")
public final class PerfLensReportController {

    private final PerfLensTraceStore traceStore;
    private final PerfLensProperties properties;

    public PerfLensReportController(PerfLensTraceStore traceStore, PerfLensProperties properties) {
        this.traceStore = traceStore;
        this.properties = properties;
    }

    /**
     * Renders the recent trace list.
     *
     * @param request servlet request used for role checks
     * @return HTML response or forbidden response
     */
    @GetMapping("/reports")
    public ResponseEntity<String> reports(HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("PerfLens viewer access denied");
        }
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><title>PerfLens Reports</title>");
        html.append("<style>body{font-family:Arial,sans-serif;margin:32px;color:#17212b}");
        html.append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccd6e0;padding:8px}");
        html.append("th{background:#edf3f8;text-align:left}</style></head><body>");
        html.append("<h1>PerfLens Reports</h1>");
        html.append("<table><tr><th>Trace</th><th>Target</th><th>Route</th><th>Duration</th><th>SQL</th></tr>");
        for (PerfLensTraceStore.StoredTrace trace : traceStore.findRecentTraces(100)) {
            html.append("<tr><td><a href=\"/perflens/reports/").append(escape(trace.traceId())).append("\">");
            html.append(escape(trace.traceId())).append("</a></td><td>").append(escape(trace.targetKey()));
            html.append("</td><td>").append(escape(trace.route())).append("</td><td>");
            html.append(trace.durationMillis()).append(" ms</td><td>").append(trace.queryCount()).append("</td></tr>");
        }
        html.append("</table></body></html>");
        return ResponseEntity.ok(html.toString());
    }

    /**
     * Renders a single trace report as plain HTML.
     *
     * @param traceId trace id
     * @param request servlet request used for role checks
     * @return HTML response, forbidden response, or not-found response
     */
    @GetMapping("/reports/{traceId}")
    public ResponseEntity<String> report(@PathVariable("traceId") String traceId, HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("PerfLens viewer access denied");
        }
        return traceStore.findTrace(traceId)
                .map(trace -> ResponseEntity.ok(render(trace)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Trace not found"));
    }

    private boolean authorized(HttpServletRequest request) {
        return !properties.getViewer().isRequireRole()
                || request.isUserInRole(properties.getViewer().getRequiredRole());
    }

    private String render(PerfLensTraceStore.StoredTrace trace) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><title>PerfLens Trace Report</title>");
        html.append("<style>body{font-family:Arial,sans-serif;margin:32px;color:#17212b}");
        html.append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccd6e0;padding:8px}");
        html.append("th{background:#edf3f8;text-align:left}</style></head><body>");
        html.append("<h1>PerfLens Trace Report</h1>");
        html.append("<p><strong>Trace:</strong> ").append(escape(trace.traceId())).append("</p>");
        html.append("<p><strong>Target:</strong> ").append(escape(trace.targetKey())).append("</p>");
        html.append("<p><strong>Route:</strong> ").append(escape(trace.route())).append("</p>");
        html.append("<p><strong>Duration:</strong> ").append(trace.durationMillis()).append(" ms</p>");
        html.append("<h2>Summary</h2><table><tr><th>SQL Events</th><th>Hibernate Events</th><th>Findings</th></tr>");
        html.append("<tr><td>").append(trace.queryCount()).append("</td><td>").append(trace.hibernateEventCount());
        html.append("</td><td>").append(trace.findingCount()).append("</td></tr></table>");
        html.append("<h2>SQL Events</h2><table><tr><th>#</th><th>Duration</th><th>SQL</th></tr>");
        for (PerfLensTraceStore.StoredSqlEvent event : traceStore.findSqlEvents(trace.traceId())) {
            html.append("<tr><td>").append(event.ordinal()).append("</td><td>").append(event.durationMillis());
            html.append(" ms</td><td><code>").append(escape(event.sql())).append("</code></td></tr>");
        }
        html.append("</table>");
        html.append("<h2>Hibernate Events</h2><table>");
        html.append("<tr><th>#</th><th>Type</th><th>Entity</th><th>Collection</th><th>Detail</th></tr>");
        for (PerfLensTraceStore.StoredHibernateEvent event : traceStore.findHibernateEvents(trace.traceId())) {
            html.append("<tr><td>").append(event.ordinal()).append("</td><td>").append(escape(event.eventType()));
            html.append("</td><td>").append(escape(event.entityName())).append("</td><td>");
            html.append(escape(event.collectionRole())).append("</td><td>").append(escape(event.detail()));
            html.append("</td></tr>");
        }
        html.append("</table>");
        html.append("<h2>Findings</h2><table>");
        html.append("<tr><th>Severity</th><th>Type</th><th>Message</th><th>Evidence</th></tr>");
        for (PerfLensTraceStore.StoredFinding finding : traceStore.findFindings(trace.traceId())) {
            html.append("<tr><td>").append(escape(finding.severity())).append("</td><td>");
            html.append(escape(finding.type())).append("</td><td>").append(escape(finding.message()));
            html.append("</td><td>").append(escape(finding.evidence())).append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
