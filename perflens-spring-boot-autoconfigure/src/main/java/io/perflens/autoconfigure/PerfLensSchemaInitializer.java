package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Runs {@code META-INF/perflens/schema.sql} at startup when the PerfLens tables do
 * not exist yet. Enabled by {@code perflens.storage.initialize-schema}.
 */
final class PerfLensSchemaInitializer implements InitializingBean {

    static final String SCHEMA = "META-INF/perflens/schema.sql";

    private final DataSource dataSource;

    PerfLensSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        PerfLensTraceContext.runIgnoring(() -> {
            if (!tablesExist()) {
                new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
            }
        });
    }

    private boolean tablesExist() {
        try {
            new JdbcTemplate(dataSource).queryForObject("select count(*) from perflens_trace where 1 = 0",
                    Integer.class);
            return true;
        }
        catch (DataAccessException ex) {
            return false;
        }
    }
}
