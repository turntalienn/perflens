package io.perflens.autoconfigure.app;

import io.perflens.autoconfigure.TraceReportListener;
import io.perflens.core.context.TraceReport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

/** Small JPA + MVC application used by the integration tests. */
@SpringBootApplication
@EnableAsync
public class LibraryApplication {

    @Bean
    CollectedReports collectedReports() {
        return new CollectedReports();
    }

    /** Collects every report so tests can assert on it. */
    public static class CollectedReports implements TraceReportListener {

        private final List<TraceReport> reports = new CopyOnWriteArrayList<>();

        @Override
        public void onReport(TraceReport report) {
            reports.add(report);
        }

        public List<TraceReport> all() {
            return reports;
        }

        public TraceReport only() {
            if (reports.size() != 1) {
                throw new AssertionError("Expected one report but got " + reports.size());
            }
            return reports.get(0);
        }

        public void clear() {
            reports.clear();
        }
    }
}
