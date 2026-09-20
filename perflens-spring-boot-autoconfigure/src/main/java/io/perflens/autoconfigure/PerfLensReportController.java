package io.perflens.autoconfigure;

import io.perflens.autoconfigure.JdbcTraceStore.StoredFinding;
import io.perflens.autoconfigure.JdbcTraceStore.StoredHibernateEvent;
import io.perflens.autoconfigure.JdbcTraceStore.StoredSql;
import io.perflens.autoconfigure.JdbcTraceStore.StoredTrace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTML pages for saved traces at {@code /perflens/reports}.
 *
 * <p>Only registered when {@code perflens.viewer.enabled=true}. By default the
 * request must pass {@code isUserInRole(perflens.viewer.required-role)}; SQL text
 * can contain business data, so keep it behind your security setup.</p>
 */
@RestController
@RequestMapping(path = "/perflens/reports", produces = MediaType.TEXT_HTML_VALUE)
final class PerfLensReportController {

    private static final int LIST_LIMIT = 200;
    private static final String STYLE = """
            <style>
            body{font:14px system-ui,sans-serif;margin:24px;color:#1b2430}
            table{border-collapse:collapse;width:100%;margin-bottom:24px}
            td,th{border:1px solid #d5dde5;padding:6px 8px;text-align:left;vertical-align:top}
            th{background:#eef3f7}code{white-space:pre-wrap;word-break:break-word}
            .CRITICAL{color:#b42318;font-weight:600}.WARNING{color:#b54708;font-weight:600}.INFO{color:#475467}
            .num{text-align:right;white-space:nowrap}
            </style>""";

    private final JdbcTraceStore store;
    private final PerfLensProperties.Viewer viewer;

    PerfLensReportController(JdbcTraceStore store, PerfLensProperties properties) {
        this.store = store;
        this.viewer = properties.getViewer();
    }

    @GetMapping
    ResponseEntity<String> list(HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
        }
        StringBuilder html = page("PerfLens traces");
        html.append("<table><tr><th>Started</th><th>Target</th><th>Request</th><th class=num>Duration</th>")
                .append("<th class=num>SQL</th><th class=num>Tx</th><th class=num>Heap</th><th>Findings</th></tr>");
        for (StoredTrace trace : store.findRecentTraces(LIST_LIMIT)) {
            html.append("<tr><td><a href=\"reports/").append(escape(trace.traceId())).append("\">")
                    .append(trace.startedAt()).append("</a></td><td>").append(escape(trace.targetKey()))
                    .append("</td><td>").append(request(trace)).append("</td><td class=num>")
                    .append(ms(trace.durationMillis())).append("</td><td class=num>").append(trace.sqlCount())
                    .append("</td><td class=num>").append(trace.transactionCount()).append("</td><td class=num>")
                    .append(trace.allocatedBytes() < 0 ? "" : String.format("%.1f MB",
                            trace.allocatedBytes() / (1024.0 * 1024)))
                    .append("</td><td>");
            if (trace.findingCount() > 0) {
                html.append("<span class=").append(escape(trace.maxSeverity())).append('>')
                        .append(trace.findingCount()).append(' ').append(escape(trace.maxSeverity()))
                        .append("</span>");
            }
            html.append("</td></tr>");
        }
        return ResponseEntity.ok(html.append("</table></body></html>").toString());
    }

    @GetMapping("/{traceId}")
    ResponseEntity<String> detail(@PathVariable("traceId") String traceId, HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
        }
        return store.findTrace(traceId)
                .map(trace -> ResponseEntity.ok(render(trace)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Trace not found"));
    }

    private String render(StoredTrace trace) {
        StringBuilder html = page("PerfLens trace " + trace.targetKey());
        html.append("<p><a href=\"../reports\">All traces</a></p><table>");
        row(html, "Trace", escape(trace.traceId()));
        row(html, "Request", request(trace));
        row(html, "Started", trace.startedAt().toString());
        row(html, "Duration", ms(trace.durationMillis()));
        row(html, "SQL", trace.sqlCount() + " statements, " + ms(trace.sqlMillis()));
        row(html, "Transactions / connections", trace.transactionCount() + " / " + trace.connectionCount());
        row(html, "Entities loaded", String.valueOf(trace.entityLoadCount()));
        if (trace.allocatedBytes() >= 0) {
            row(html, "Heap allocated", String.format("%.1f MB", trace.allocatedBytes() / (1024.0 * 1024)));
            row(html, "CPU", ms(trace.cpuMicros() / 1000.0));
        }
        row(html, "GC during request (JVM-wide)", trace.gcCount() + " pauses, " + trace.gcMillis() + " ms");
        if (trace.errorMessage() != null) {
            row(html, "Error", escape(trace.errorMessage()));
        }
        if (trace.droppedEvents() > 0) {
            row(html, "Dropped events", trace.droppedEvents() + " (per-trace cap reached)");
        }
        html.append("</table>");

        List<StoredFinding> findings = store.findFindings(trace.traceId());
        html.append("<h2>Findings</h2><table><tr><th>Severity</th><th>Type</th><th>Message</th><th>Evidence</th></tr>");
        for (StoredFinding finding : findings) {
            html.append("<tr><td class=").append(escape(finding.severity())).append('>')
                    .append(escape(finding.severity())).append("</td><td>").append(escape(finding.type()))
                    .append("</td><td>").append(escape(finding.message())).append("</td><td><code>")
                    .append(escape(finding.evidence())).append("</code></td></tr>");
        }
        html.append("</table><h2>SQL</h2><table><tr><th>#</th><th class=num>Time</th><th>Statement</th></tr>");
        for (StoredSql sql : store.findSql(trace.traceId())) {
            html.append("<tr><td>").append(sql.seq()).append("</td><td class=num>").append(ms(sql.durationMillis()))
                    .append("</td><td><code>").append(escape(sql.sql())).append("</code>");
            if (!sql.success()) {
                html.append("<br><span class=CRITICAL>").append(escape(sql.errorMessage())).append("</span>");
            }
            html.append("</td></tr>");
        }
        html.append("</table><h2>Hibernate</h2><table>")
                .append("<tr><th>#</th><th>Event</th><th>Entity / collection</th><th>Detail</th></tr>");
        for (StoredHibernateEvent event : store.findHibernateEvents(trace.traceId())) {
            String subject = event.entityName() != null ? event.entityName() : event.collectionRole();
            html.append("<tr><td>").append(event.seq()).append("</td><td>").append(escape(event.eventType()))
                    .append("</td><td>").append(escape(subject)).append("</td><td>").append(escape(event.detail()))
                    .append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private boolean authorized(HttpServletRequest request) {
        return !viewer.isRequireRole() || request.isUserInRole(viewer.getRequiredRole());
    }

    private static StringBuilder page(String title) {
        return new StringBuilder("<!doctype html><html><head><meta charset=utf-8><title>").append(escape(title))
                .append("</title>").append(STYLE).append("</head><body><h1>").append(escape(title)).append("</h1>");
    }

    private static void row(StringBuilder html, String name, String value) {
        html.append("<tr><th>").append(name).append("</th><td>").append(value).append("</td></tr>");
    }

    private static String request(StoredTrace trace) {
        if (trace.httpMethod() == null) {
            return "method call";
        }
        return escape(trace.httpMethod() + " " + trace.route()) + " &rarr; " + trace.statusCode();
    }

    private static String ms(double millis) {
        return String.format("%.1f ms", millis);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
