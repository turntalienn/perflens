package io.perflens.autoconfigure;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Main Spring Boot auto-configuration entry point for PerfLens.
 *
 * <p>The auto-configuration is intentionally assembled from small beans. Each
 * bean has one job: control lookup, schema initialization, trace storage, MVC
 * boundary detection, method span detection, SQL capture, Hibernate event
 * registration, or report rendering.</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@EnableConfigurationProperties(PerfLensProperties.class)
@ConditionalOnProperty(prefix = "perflens", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PerfLensAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock perfLensClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    PerfLensControlService perfLensControlService(DataSource dataSource, Clock clock) {
        return new PerfLensControlService(dataSource, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    PerfLensTraceStore perfLensTraceStore(DataSource dataSource, Clock clock) {
        return new PerfLensTraceStore(dataSource, clock);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    PerfLensSchemaInitializer perfLensSchemaInitializer(DataSource dataSource, PerfLensProperties properties) {
        return new PerfLensSchemaInitializer(dataSource, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    static PerfLensDataSourceBeanPostProcessor perfLensDataSourceBeanPostProcessor() {
        return new PerfLensDataSourceBeanPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PerfLensControlService.class, PerfLensTraceStore.class})
    PerfLensTraceAspect perfLensTraceAspect(PerfLensControlService controlService, PerfLensTraceStore traceStore,
            Clock clock) {
        return new PerfLensTraceAspect(controlService, traceStore, clock);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.servlet.HandlerInterceptor")
    @ConditionalOnBean({PerfLensControlService.class, PerfLensTraceStore.class})
    PerfLensHandlerInterceptor perfLensHandlerInterceptor(PerfLensControlService controlService,
            PerfLensTraceStore traceStore, Clock clock) {
        return new PerfLensHandlerInterceptor(controlService, traceStore, clock);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
    @ConditionalOnBean(PerfLensHandlerInterceptor.class)
    PerfLensWebMvcConfigurer perfLensWebMvcConfigurer(PerfLensHandlerInterceptor interceptor) {
        return new PerfLensWebMvcConfigurer(interceptor);
    }

    @Bean
    @ConditionalOnClass(name = "org.hibernate.integrator.spi.Integrator")
    HibernatePropertiesCustomizer perfLensHibernatePropertiesCustomizer() {
        return new PerfLensHibernatePropertiesCustomizer();
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "perflens.viewer", name = "enabled")
    @ConditionalOnBean(PerfLensTraceStore.class)
    PerfLensReportController perfLensReportController(PerfLensTraceStore traceStore, PerfLensProperties properties) {
        return new PerfLensReportController(traceStore, properties);
    }
}
