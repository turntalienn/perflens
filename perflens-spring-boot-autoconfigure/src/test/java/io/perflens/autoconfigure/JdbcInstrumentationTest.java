package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.SqlEvent;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.TraceBoundary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JdbcInstrumentationTest {

    private static HikariDataSource hikari;
    private static DataSource dataSource;

    @BeforeAll
    static void createDataSource() throws Exception {
        hikari = new HikariDataSource();
        hikari.setJdbcUrl("jdbc:h2:mem:perflens_jdbc;DB_CLOSE_DELAY=-1");
        hikari.setMaximumPoolSize(4);
        dataSource = (DataSource) new PerfLensDataSourceBeanPostProcessor()
                .postProcessAfterInitialization(hikari, "dataSource");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table item (id int primary key, name varchar(20))");
            statement.execute("insert into item values (1, 'a'), (2, 'b')");
        }
    }

    @AfterAll
    static void closeDataSource() {
        hikari.close();
    }

    @AfterEach
    void clearContext() {
        PerfLensTraceContext.clear();
    }

    @Test
    void preparedStatementsCarryAHashOfTheirBoundValues() throws Exception {
        TraceReport report = trace(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("select name from item where id = ?")) {
                for (int id : new int[] {1, 1, 2}) {
                    statement.setInt(1, id);
                    statement.executeQuery().close();
                }
            }
        });

        assertThat(report.sqlEvents()).extracting(SqlEvent::sql).containsOnly("select name from item where id = ?");
        int[] hashes = report.sqlEvents().stream().mapToInt(SqlEvent::parameterHash).toArray();
        assertThat(hashes[0]).isEqualTo(hashes[1]).isNotEqualTo(hashes[2]).isNotZero();
    }

    @Test
    void transactionsAreTimedFromAutoCommitOffToCommit() throws Exception {
        TraceReport report = trace(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("update item set name = 'c' where id = 1");
                    statement.executeQuery("select count(*) from item").close();
                }
                connection.rollback();
                connection.setAutoCommit(true);
            }
        });

        assertThat(report.transactions()).singleElement().satisfies(tx -> {
            assertThat(tx.committed()).isFalse();
            assertThat(tx.statementCount()).isEqualTo(2);
            assertThat(tx.sqlNanos()).isPositive().isLessThanOrEqualTo(tx.durationNanos());
        });
    }

    @Test
    void checkoutsRecordWaitHeldCountAndHikariGauges() throws Exception {
        TraceReport report = trace(() -> {
            try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
                assertThat(second.unwrap(Connection.class)).isNotNull();
            }
        });

        assertThat(report.connections()).hasSize(2);
        assertThat(report.connections().get(1).heldByTrace()).isEqualTo(2);
        assertThat(report.connections().get(1).poolActive()).isEqualTo(2);
        assertThat(report.connections().get(1).poolMax()).isEqualTo(4);
    }

    @Test
    void lockTimeoutKeepsTheDriverErrorCodes() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().executeUpdate("update item set name = 'held' where id = 2");

            TraceReport report = trace(() -> {
                try (Connection waiter = dataSource.getConnection(); Statement statement = waiter.createStatement()) {
                    statement.execute("set lock_timeout 100");
                    assertThatThrownBy(() -> statement.executeUpdate("update item set name = 'x' where id = 2"))
                            .isInstanceOf(SQLException.class);
                }
            });
            holder.rollback();

            SqlEvent failed = report.sqlEvents().get(report.sqlEvents().size() - 1);
            assertThat(failed.success()).isFalse();
            assertThat(failed.sqlState()).isEqualTo("HYT00");
            assertThat(failed.vendorCode()).isEqualTo(50200);
        }
    }

    @Test
    void nothingIsRecordedOutsideATrace() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("select 1").close();
        }
        assertThat(PerfLensTraceContext.isActive()).isFalse();
    }

    private static TraceReport trace(JdbcWork work) throws Exception {
        Clock clock = Clock.systemUTC();
        PerfLensTraceContext.startRoot("jdbc", "", TraceBoundary.SERVICE_METHOD, null, null, clock);
        work.run();
        return PerfLensTraceContext.closeRoot(null, null, clock);
    }

    @FunctionalInterface
    private interface JdbcWork {
        void run() throws Exception;
    }
}
