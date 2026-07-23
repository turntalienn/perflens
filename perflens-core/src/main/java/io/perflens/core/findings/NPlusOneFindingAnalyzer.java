package io.perflens.core.findings;

import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects likely N+1 query candidates from repeated SQL evidence.
 *
 * <p>This analyzer deliberately reports a candidate rather than a guaranteed
 * defect. Repeated normalized SQL is strong evidence, but the caller still needs
 * domain context to decide whether the pattern is harmful or expected.</p>
 */
public final class NPlusOneFindingAnalyzer {

    private static final int REPEATED_SELECT_THRESHOLD = 4;

    /**
     * Analyzes a completed trace for repeated select query shapes.
     *
     * @param report completed trace report
     * @return findings sorted by highest repeated count first
     */
    public List<TraceFinding> analyze(TraceReport report) {
        Map<String, List<SqlEvent>> repeatedSelects = report.sqlEvents().stream()
                .filter(SqlEvent::success)
                .filter(event -> event.normalizedSql().startsWith("select "))
                .collect(Collectors.groupingBy(SqlEvent::normalizedSql));

        List<TraceFinding> findings = new ArrayList<>();
        repeatedSelects.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= REPEATED_SELECT_THRESHOLD)
                .sorted(Comparator.comparingInt((Map.Entry<String, List<SqlEvent>> entry) -> entry.getValue().size())
                        .reversed())
                .forEach(entry -> findings.add(toFinding(entry.getKey(), entry.getValue())));
        return findings;
    }

    private TraceFinding toFinding(String normalizedSql, List<SqlEvent> events) {
        String evidence = events.size() + " repeated executions of normalized SQL: " + normalizedSql;
        String message = "Possible N+1 query pattern detected from repeated SELECT executions.";
        return new TraceFinding("N_PLUS_ONE_CANDIDATE", FindingSeverity.WARNING, message, evidence);
    }
}
