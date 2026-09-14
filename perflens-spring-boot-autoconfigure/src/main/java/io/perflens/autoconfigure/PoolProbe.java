package io.perflens.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import org.springframework.util.ClassUtils;

/**
 * Reads connection pool gauges at checkout time. Only HikariCP is supported; other
 * pools report unknown ({@code -1}) values and are still timed.
 */
@FunctionalInterface
interface PoolProbe {

    /** Probe for pools that expose no gauges. */
    PoolProbe NONE = () -> PoolStats.UNKNOWN;

    /** @return current gauges */
    PoolStats read();

    /**
     * @param dataSource the DataSource being instrumented
     * @return a probe for its pool
     */
    static PoolProbe forDataSource(DataSource dataSource) {
        if (ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource", PoolProbe.class.getClassLoader())) {
            return Hikari.probe(dataSource);
        }
        return NONE;
    }

    /**
     * @param active connections in use
     * @param max maximum pool size
     * @param pending threads waiting for a connection
     */
    record PoolStats(int active, int max, int pending) {

        static final PoolStats UNKNOWN = new PoolStats(-1, -1, -1);
    }

    /** Kept in its own class so Hikari types are only loaded when Hikari is present. */
    final class Hikari {

        private Hikari() {
        }

        static PoolProbe probe(DataSource dataSource) {
            if (!(dataSource instanceof HikariDataSource hikari)) {
                return NONE;
            }
            return () -> {
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                return pool == null ? PoolStats.UNKNOWN
                        : new PoolStats(pool.getActiveConnections(), hikari.getMaximumPoolSize(),
                                pool.getThreadsAwaitingConnection());
            };
        }
    }
}
