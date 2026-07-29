package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Optional schema initializer for local development and tests.
 *
 * <p>The initializer is disabled by default so production applications do not
 * experience surprise DDL at startup. When enabled, it runs inside the PerfLens
 * ignore scope so schema creation is never counted as business API SQL.</p>
 */
public final class PerfLensSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;
    private final PerfLensProperties properties;

    public PerfLensSchemaInitializer(DataSource dataSource, PerfLensProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getStorage().isInitializeSchema()) {
            return;
        }
        PerfLensTraceContext.runIgnoring(() ->
                PerfLensSchema.statements().forEach(jdbcTemplate::execute));
    }
}
