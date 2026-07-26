package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads DB-backed control rows that decide whether a target key should be traced.
 *
 * <p>The service deliberately checks the database every time an annotated root
 * boundary is reached. That gives operators near-real-time enable/disable
 * behavior without restarting the application.</p>
 */
public final class PerfLensControlService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PerfLensControlService(DataSource dataSource, Clock clock) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /**
     * Returns whether the target key is currently enabled.
     *
     * @param targetKey annotation key
     * @return {@code true} when an enabled, unexpired control row exists
     */
    public boolean isEnabled(String targetKey) {
        return findControl(targetKey)
                .map(TraceControl::enabled)
                .orElse(false);
    }

    /**
     * Reads the current control row for a target key.
     *
     * @param targetKey annotation key
     * @return control row when available
     */
    public Optional<TraceControl> findControl(String targetKey) {
        return PerfLensTraceContext.callIgnoring(() -> {
            try {
                return jdbcTemplate.query("""
                        select enabled, enabled_until, capture_parameters
                        from perflens_control
                        where target_key = ?
                        """, rs -> {
                    if (!rs.next()) {
                        return Optional.<TraceControl>empty();
                    }
                    Timestamp enabledUntil = rs.getTimestamp("enabled_until");
                    boolean unexpired = enabledUntil == null || enabledUntil.toInstant().isAfter(clock.instant());
                    return Optional.of(new TraceControl(rs.getBoolean("enabled") && unexpired,
                            rs.getBoolean("capture_parameters")));
                }, targetKey);
            }
            catch (DataAccessException ex) {
                return Optional.<TraceControl>empty();
            }
        });
    }

    /**
     * Minimal control-row projection used by runtime decisions.
     *
     * @param enabled whether capture should run
     * @param captureParameters whether parameter capture is allowed
     */
    public record TraceControl(boolean enabled, boolean captureParameters) {
    }
}
