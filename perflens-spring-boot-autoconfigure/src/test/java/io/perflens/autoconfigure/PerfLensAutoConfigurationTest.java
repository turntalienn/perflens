package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;

class PerfLensAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    PerfLensAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:perflens_autoconfig");

    @Test
    void defaultsLogOnlyAndCreateNoTablesOrRoutes() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PerfLensTracer.class).hasSingleBean(TraceControl.class)
                    .hasSingleBean(TraceReportListener.class)
                    .doesNotHaveBean(JdbcTraceStore.class)
                    .doesNotHaveBean(PerfLensSchemaInitializer.class)
                    .doesNotHaveBean(PerfLensReportController.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class)
                    .isInstanceOf(PerfLensDataSourceBeanPostProcessor.Instrumented.class);
            assertThat(context.getBean(TraceControl.class).isEnabled("anything")).isFalse();
        });
    }

    @Test
    void masterSwitchTurnsEverythingOff() {
        runner.withPropertyValues("perflens.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PerfLensTracer.class);
            assertThat(context.getBean(DataSource.class))
                    .isNotInstanceOf(PerfLensDataSourceBeanPostProcessor.Instrumented.class);
        });
    }

    @Test
    void storageIsOptIn() {
        runner.withPropertyValues("perflens.storage.enabled=true").run(context ->
                assertThat(context).hasSingleBean(JdbcTraceStore.class));
    }

    @Test
    void wildcardTargetEnablesEveryKey() {
        runner.withPropertyValues("perflens.control.targets=*").run(context ->
                assertThat(context.getBean(TraceControl.class).isEnabled("anything")).isTrue());
    }

    @Test
    void applicationTraceControlReplacesTheDefault() {
        runner.withBean(TraceControl.class, () -> key -> key.startsWith("api-")).run(context -> {
            TraceControl control = context.getBean(TraceControl.class);
            assertThat(control.isEnabled("api-orders")).isTrue();
            assertThat(control.isEnabled("orders")).isFalse();
        });
    }

    @Test
    void registersATaskDecoratorUnlessTheApplicationHasOne() {
        runner.run(context -> assertThat(context).hasSingleBean(PerfLensTaskDecorator.class));
        runner.withBean(TaskDecorator.class, () -> runnable -> runnable).run(context ->
                assertThat(context).doesNotHaveBean(PerfLensTaskDecorator.class).hasSingleBean(TaskDecorator.class));
    }

    @Test
    void worksWithoutADataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PerfLensAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PerfLensTracer.class));
    }
}
