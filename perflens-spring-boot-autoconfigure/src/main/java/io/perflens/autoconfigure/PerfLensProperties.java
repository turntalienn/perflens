package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.findings.AnalysisThresholds;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code perflens.*} configuration.
 *
 * <p>Defaults are safe for any environment: nothing is traced until a target is
 * enabled, reports only go to the log, and no tables or HTTP routes are created.</p>
 */
@ConfigurationProperties(prefix = "perflens")
public class PerfLensProperties {

    /** Master switch. When false, PerfLens registers no beans and wraps no DataSource. */
    private boolean enabled = true;

    /**
     * Trace every Spring MVC request, not only {@code @PerfLensTrace} handlers. The
     * trace key is "METHOD /route/pattern". Handy in dev and test.
     */
    private boolean traceAllRequests;

    /** Analyze and publish reports on a background thread instead of the request thread. */
    private boolean asyncReporting = true;

    /** Cap on SQL plus Hibernate events kept per trace. */
    private int maxEventsPerTrace = PerfLensTraceContext.DEFAULT_MAX_EVENTS;

    /** Emit io.perflens.* Java Flight Recorder events. They cost nothing unless a recording is running. */
    private boolean jfr = true;

    /**
     * Register a TaskDecorator so @Async methods and Spring-managed executors join the
     * caller's trace. Skipped when the application defines its own TaskDecorator.
     */
    private boolean propagateToExecutors = true;

    private final Control control = new Control();
    private final Analysis analysis = new Analysis();
    private final Log log = new Log();
    private final Storage storage = new Storage();
    private final Viewer viewer = new Viewer();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTraceAllRequests() {
        return traceAllRequests;
    }

    public void setTraceAllRequests(boolean traceAllRequests) {
        this.traceAllRequests = traceAllRequests;
    }

    public boolean isAsyncReporting() {
        return asyncReporting;
    }

    public void setAsyncReporting(boolean asyncReporting) {
        this.asyncReporting = asyncReporting;
    }

    public int getMaxEventsPerTrace() {
        return maxEventsPerTrace;
    }

    public void setMaxEventsPerTrace(int maxEventsPerTrace) {
        this.maxEventsPerTrace = maxEventsPerTrace;
    }

    public boolean isJfr() {
        return jfr;
    }

    public void setJfr(boolean jfr) {
        this.jfr = jfr;
    }

    public boolean isPropagateToExecutors() {
        return propagateToExecutors;
    }

    public void setPropagateToExecutors(boolean propagateToExecutors) {
        this.propagateToExecutors = propagateToExecutors;
    }

    public Control getControl() {
        return control;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public Log getLog() {
        return log;
    }

    public Storage getStorage() {
        return storage;
    }

    public Viewer getViewer() {
        return viewer;
    }

    /** Which {@code @PerfLensTrace} keys are switched on. */
    public static class Control {

        /** Keys to trace. Use "*" to trace every annotated boundary. */
        private Set<String> targets = new LinkedHashSet<>();

        /** Also read enabled keys from the perflens_control table, so they can be toggled at runtime. */
        private boolean database;

        /** How long a read of the perflens_control table is reused. */
        private Duration refreshInterval = Duration.ofSeconds(10);

        public Set<String> getTargets() {
            return targets;
        }

        public void setTargets(Set<String> targets) {
            this.targets = targets;
        }

        public boolean isDatabase() {
            return database;
        }

        public void setDatabase(boolean database) {
            this.database = database;
        }

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }
    }

    /** Thresholds for the built-in analyzers. */
    public static class Analysis {

        /** Executions of one SELECT shape, with different parameters, that count as an N+1 pattern. */
        private int repeatedSelectThreshold = 4;

        /** Statement duration that counts as a slow query. */
        private Duration slowQuery = Duration.ofMillis(200);

        /** Transaction duration that counts as a slow transaction. */
        private Duration slowTransaction = Duration.ofMillis(500);

        /** Connection checkout wait that counts as pool pressure. */
        private Duration connectionWait = Duration.ofMillis(50);

        /** Heap allocated by one request that counts as high. */
        private DataSize allocation = DataSize.ofMegabytes(64);

        /** GC pause time overlapping one request that counts as GC pressure. */
        private Duration gcTime = Duration.ofMillis(100);

        /** Entities Hibernate loads in one request that count as a large persistence context. */
        private int entityLoadThreshold = 1_000;

        public int getRepeatedSelectThreshold() {
            return repeatedSelectThreshold;
        }

        public void setRepeatedSelectThreshold(int repeatedSelectThreshold) {
            this.repeatedSelectThreshold = repeatedSelectThreshold;
        }

        public Duration getSlowQuery() {
            return slowQuery;
        }

        public void setSlowQuery(Duration slowQuery) {
            this.slowQuery = slowQuery;
        }

        public Duration getSlowTransaction() {
            return slowTransaction;
        }

        public void setSlowTransaction(Duration slowTransaction) {
            this.slowTransaction = slowTransaction;
        }

        public Duration getConnectionWait() {
            return connectionWait;
        }

        public void setConnectionWait(Duration connectionWait) {
            this.connectionWait = connectionWait;
        }

        public DataSize getAllocation() {
            return allocation;
        }

        public void setAllocation(DataSize allocation) {
            this.allocation = allocation;
        }

        public Duration getGcTime() {
            return gcTime;
        }

        public void setGcTime(Duration gcTime) {
            this.gcTime = gcTime;
        }

        public int getEntityLoadThreshold() {
            return entityLoadThreshold;
        }

        public void setEntityLoadThreshold(int entityLoadThreshold) {
            this.entityLoadThreshold = entityLoadThreshold;
        }

        AnalysisThresholds toThresholds() {
            return new AnalysisThresholds(repeatedSelectThreshold, slowQuery, slowTransaction, connectionWait,
                    allocation.toBytes(), gcTime, entityLoadThreshold);
        }
    }

    /** Log output. */
    public static class Log {

        /** Log a summary per trace: WARN when it has findings, DEBUG otherwise. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Persisting reports to the perflens_* tables. */
    public static class Storage {

        /** Save reports to the database. Requires the schema in META-INF/perflens/schema.sql. */
        private boolean enabled;

        /** Create the tables at startup if they are missing. Meant for dev and tests; use migrations elsewhere. */
        private boolean initializeSchema;

        /** Age after which saved traces are deleted. */
        private Duration retention = Duration.ofDays(7);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    /** Read-only HTML viewer at /perflens/reports. Requires storage. */
    public static class Viewer {

        /** Expose the viewer routes. */
        private boolean enabled;

        /** Only serve requests whose user has the required role. */
        private boolean requireRole = true;

        /** Role checked with HttpServletRequest#isUserInRole. */
        private String requiredRole = "PERFLENS_ADMIN";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequireRole() {
            return requireRole;
        }

        public void setRequireRole(boolean requireRole) {
            this.requireRole = requireRole;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole;
        }
    }
}
