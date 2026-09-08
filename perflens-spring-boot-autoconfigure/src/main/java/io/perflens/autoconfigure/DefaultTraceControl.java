package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enables keys listed in {@code perflens.control.targets} and, when a DataSource is
 * given, keys with an enabled, unexpired row in {@code perflens_control}.
 *
 * <p>The table is read in full at most once per refresh interval and only one
 * thread refreshes at a time; other threads keep using the previous snapshot. The
 * read runs outside any caller transaction, so a missing table cannot poison it.
 * Read failures disable database control until the next refresh.</p>
 */
final class DefaultTraceControl implements TraceControl {

    private static final Log logger = LogFactory.getLog(DefaultTraceControl.class);

    private final Set<String> targets;
    private final boolean allTargets;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate outsideTransaction;
    private final Clock clock;
    private final long refreshNanos;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Map<String, Instant> databaseTargets = Map.of();
    private volatile long nextRefresh = System.nanoTime();
    private volatile boolean warned;

    /**
     * @param targets keys enabled by configuration; "*" enables all
     * @param dataSource DataSource holding perflens_control, or {@code null} to use configuration only
     * @param refreshInterval how long a table read is reused
     * @param clock time source for {@code enabled_until}
     */
    DefaultTraceControl(Set<String> targets, DataSource dataSource, Duration refreshInterval, Clock clock) {
        this.targets = Set.copyOf(targets);
        this.allTargets = targets.contains("*");
        this.jdbcTemplate = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.outsideTransaction = dataSource == null ? null
                : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        if (outsideTransaction != null) {
            outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        }
        this.refreshNanos = refreshInterval.toNanos();
        this.clock = clock;
    }

    @Override
    public boolean isEnabled(String targetKey) {
        if (allTargets || targets.contains(targetKey)) {
            return true;
        }
        if (jdbcTemplate == null) {
            return false;
        }
        refreshIfStale();
        Instant until = databaseTargets.get(targetKey);
        return until != null && until.isAfter(clock.instant());
    }

    private void refreshIfStale() {
        long now = System.nanoTime();
        if (now - nextRefresh < 0 || !refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            databaseTargets = PerfLensTraceContext.callIgnoring(() -> outsideTransaction.execute(status -> load()));
        }
        catch (DataAccessException ex) {
            databaseTargets = Map.of();
            if (!warned) {
                warned = true;
                logger.warn("PerfLens cannot read perflens_control, database control is off until it can: "
                        + ex.getMessage());
            }
        }
        finally {
            nextRefresh = now + refreshNanos;
            refreshing.set(false);
        }
    }

    private Map<String, Instant> load() {
        Map<String, Instant> enabled = new HashMap<>();
        jdbcTemplate.query("select target_key, enabled_until from perflens_control where enabled = ?", rs -> {
            Timestamp until = rs.getTimestamp("enabled_until");
            enabled.put(rs.getString("target_key"), until == null ? Instant.MAX : until.toInstant());
        }, true);
        return Map.copyOf(enabled);
    }
}
