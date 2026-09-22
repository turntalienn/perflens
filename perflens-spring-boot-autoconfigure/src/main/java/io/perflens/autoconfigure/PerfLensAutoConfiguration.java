package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.findings.TraceAnalyzer;
import io.perflens.core.findings.TraceAnalyzers;
import io.perflens.core.jfr.JfrSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration for PerfLens.
 *
 * <p>Always registers the DataSource instrumentation, the {@link TraceControl}, the
 * {@link PerfLensTracer}, the log listener and, unless the application has one, a
 * {@link PerfLensTaskDecorator} so Spring executors propagate traces. Everything
 * else depends on what the application has: the MVC interceptor needs Spring MVC,
 * method tracing needs AspectJ, Hibernate events need Hibernate, and
 * storage/viewer need a single DataSource plus their properties.</p>
 *
 * <p>Extension points: declare a {@link TraceControl} bean to replace the default,
 * and any number of {@link TraceAnalyzer} or {@link TraceReportListener} beans to
 * add analysis or report destinations.</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
        before = {HibernateJpaAutoConfiguration.class, TaskExecutionAutoConfiguration.class})
@ConditionalOnBooleanProperty(name = "perflens.enabled", matchIfMissing = true)
@EnableConfigurationProperties(PerfLensProperties.class)
public class PerfLensAutoConfiguration {

    @Bean
    static PerfLensDataSourceBeanPostProcessor perfLensDataSourceBeanPostProcessor() {
        return new PerfLensDataSourceBeanPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    TraceControl perfLensTraceControl(PerfLensProperties properties, ObjectProvider<DataSource> dataSource) {
        PerfLensProperties.Control control = properties.getControl();
        return new DefaultTraceControl(control.getTargets(), control.isDatabase() ? dataSource.getIfUnique() : null,
                control.getRefreshInterval(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    PerfLensTracer perfLensTracer(TraceControl control, PerfLensProperties properties,
            ObjectProvider<TraceAnalyzer> customAnalyzers, ObjectProvider<TraceReportListener> listeners) {
        PerfLensTraceContext.setMaxEventsPerTrace(properties.getMaxEventsPerTrace());
        JfrSupport.setEnabled(properties.isJfr());
        List<TraceAnalyzer> analyzers = new ArrayList<>(
                TraceAnalyzers.defaults(properties.getAnalysis().toThresholds()));
        customAnalyzers.orderedStream().forEach(analyzers::add);
        return new PerfLensTracer(control, analyzers, listeners.orderedStream().toList(), Clock.systemUTC(),
                properties.isAsyncReporting());
    }

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    @ConditionalOnBooleanProperty(name = "perflens.propagate-to-executors", matchIfMissing = true)
    PerfLensTaskDecorator perfLensTaskDecorator() {
        return new PerfLensTaskDecorator();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "perflens.log.enabled", matchIfMissing = true)
    TraceReportListener perfLensLoggingReportListener() {
        return new LoggingReportListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.weaver.Advice")
    static class MethodTracingConfiguration {

        @Bean
        PerfLensTraceAspect perfLensTraceAspect(PerfLensTracer tracer) {
            return new PerfLensTraceAspect(tracer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class WebMvcTracingConfiguration {

        @Bean
        WebMvcConfigurer perfLensWebMvcConfigurer(PerfLensTracer tracer, PerfLensProperties properties) {
            PerfLensHandlerInterceptor interceptor = new PerfLensHandlerInterceptor(tracer,
                    properties.isTraceAllRequests());
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor).excludePathPatterns("/perflens/**");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.hibernate.integrator.spi.Integrator",
            "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean"})
    static class HibernateConfiguration {

        @Bean
        HibernatePropertiesCustomizer perfLensHibernatePropertiesCustomizer() {
            return HibernateEventCollector::register;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSingleCandidate(DataSource.class)
    static class StorageConfiguration {

        @Bean
        @ConditionalOnBooleanProperty(name = "perflens.storage.initialize-schema")
        PerfLensSchemaInitializer perfLensSchemaInitializer(DataSource dataSource) {
            return new PerfLensSchemaInitializer(dataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "perflens.storage.enabled")
        JdbcTraceStore perfLensJdbcTraceStore(DataSource dataSource, PerfLensProperties properties) {
            return new JdbcTraceStore(dataSource, Clock.systemUTC(), properties.getStorage().getRetention());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnProperty(prefix = "perflens", name = {"storage.enabled", "viewer.enabled"}, havingValue = "true")
        PerfLensReportController perfLensReportController(JdbcTraceStore store, PerfLensProperties properties) {
            return new PerfLensReportController(store, properties);
        }
    }
}
