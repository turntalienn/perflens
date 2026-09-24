package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.findings.AnalysisThresholds;
import io.perflens.core.findings.LockContentionAnalyzer;
import io.perflens.core.findings.TraceAnalyzers;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs schema.sql, a store round trip and a real lock timeout against PostgreSQL
 * and MySQL. Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RealDatabaseTest {

    static Stream<JdbcDatabaseContainer<?>> databases() {
        return Stream.of(new PostgreSQLContainer<>("postgres:16-alpine"), new MySQLContainer<>("mysql:8.4"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void schemaAndStoreWork(JdbcDatabaseContainer<?> database) {
        try (database; HikariDataSource dataSource = new HikariDataSource()) {
            database.start();
            dataSource.setJdbcUrl(database.getJdbcUrl());
            dataSource.setUsername(database.getUsername());
            dataSource.setPassword(database.getPassword());

            PerfLensSchemaInitializer initializer = new PerfLensSchemaInitializer(dataSource);
            initializer.afterPropertiesSet();
            initializer.afterPropertiesSet();

            JdbcTraceStore store = new JdbcTraceStore(dataSource, Clock.systemUTC(), Duration.ofDays(7));
            TraceReport report = sampleReport();
            store.onReport(report);

            assertThat(store.findTrace(report.traceId())).hasValueSatisfying(trace -> {
                assertThat(trace.sqlCount()).isEqualTo(5);
                assertThat(trace.maxSeverity()).isEqualTo("WARNING");
            });
            assertThat(store.findSql(report.traceId())).hasSize(5);
            assertThat(store.findHibernateEvents(report.traceId())).hasSize(1);
            assertThat(store.findFindings(report.traceId())).hasSize(1);
            assertThat(store.findRecentTraces(10)).hasSize(1);

            new JdbcTemplate(dataSource).update(
                    "insert into perflens_control(target_key, enabled) values ('orders', true)");
            DefaultTraceControl control = new DefaultTraceControl(java.util.Set.of(), dataSource, Duration.ZERO,
                    Clock.systemUTC());
            assertThat(control.isEnabled("orders")).isTrue();
            assertThat(control.isEnabled("other")).isFalse();

            assertThat(store.deleteStartedBefore(Instant.now().plusSeconds(60))).isEqualTo(1);
            assertThat(store.findSql(report.traceId())).isEmpty();

            assertLockTimeoutIsDetected(dataSource, database instanceof MySQLContainer);
        }
    }

    /** Holds a row lock on one connection and times out updating the same row on another. */
    private static void assertLockTimeoutIsDetected(HikariDataSource hikari, boolean mysql) {
        DataSource dataSource = (DataSource) new PerfLensDataSourceBeanPostProcessor()
                .postProcessAfterInitialization(hikari, "dataSource");
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("create table plate (id int primary key, status varchar(20))");
        setup.update("insert into plate values (1, 'NEW')");

        Clock clock = Clock.systemUTC();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().executeUpdate("update plate set status = 'HELD' where id = 1");

            PerfLensTraceContext.startRoot("pi-save", "", TraceBoundary.HTTP_REQUEST, "/pi", "POST", clock);
            try (Connection waiter = dataSource.getConnection(); Statement statement = waiter.createStatement()) {
                statement.execute(mysql ? "set innodb_lock_wait_timeout = 1" : "set lock_timeout = '300ms'");
                assertThatThrownBy(() -> statement.executeUpdate("update plate set status = 'DONE' where id = 1"))
                        .isInstanceOf(SQLException.class);
            }
            TraceReport report = TraceAnalyzers.analyze(PerfLensTraceContext.closeRoot(500, null, clock),
                    TraceAnalyzers.defaults(AnalysisThresholds.defaults()));
            holder.rollback();

            assertThat(report.findingsOfType(LockContentionAnalyzer.TYPE)).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).contains("Timed out waiting for a row lock"));
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        finally {
            PerfLensTraceContext.clear();
        }
    }

    private static TraceReport sampleReport() {
        Clock clock = Clock.systemUTC();
        PerfLensTraceContext.startRoot("orders", "Orders", TraceBoundary.HTTP_REQUEST, "/orders", "GET", clock);
        for (int id = 1; id <= 5; id++) {
            PerfLensTraceContext.recordSql("select * from line where order_id = ?", id, 1_000_000, null);
        }
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD, "Order", null, "1");
        TraceReport raw = PerfLensTraceContext.closeRoot(200, null, clock);
        return TraceAnalyzers.analyze(raw, TraceAnalyzers.defaults(AnalysisThresholds.defaults()));
    }
}
