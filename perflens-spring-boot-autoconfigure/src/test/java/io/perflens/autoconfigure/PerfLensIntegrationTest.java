package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.perflens.autoconfigure.app.Author;
import io.perflens.autoconfigure.app.AuthorRepository;
import io.perflens.autoconfigure.app.Book;
import io.perflens.autoconfigure.app.BookRepository;
import io.perflens.autoconfigure.app.LibraryApplication;
import io.perflens.autoconfigure.app.LibraryApplication.CollectedReports;
import io.perflens.core.context.TraceFinding;
import io.perflens.core.context.TraceReport;
import io.perflens.core.findings.ConnectionPoolAnalyzer;
import io.perflens.core.findings.DuplicateQueryAnalyzer;
import io.perflens.core.findings.NPlusOneAnalyzer;
import io.perflens.core.findings.SlowTransactionAnalyzer;
import io.perflens.core.model.HibernateEventType;
import io.perflens.core.model.TraceBoundary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = LibraryApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:perflens_it;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "perflens.async-reporting=false",
        "perflens.control.targets=authors-lazy,authors-fetch-join,count-twice,slow-tx,nested-tx,nightly-job,parallel-plates",
        "perflens.control.database=true",
        "perflens.control.refresh-interval=0s",
        "perflens.analysis.slow-transaction=50ms",
        "perflens.storage.enabled=true",
        "perflens.storage.initialize-schema=true",
        "perflens.viewer.enabled=true",
        "perflens.viewer.require-role=false"
})
@AutoConfigureMockMvc
class PerfLensIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CollectedReports reports;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcTraceStore store;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seed(@Autowired AuthorRepository authors, @Autowired BookRepository books) {
        books.deleteAll();
        authors.deleteAll();
        for (int i = 1; i <= 5; i++) {
            Author author = authors.save(new Author("author-" + i));
            books.save(new Book("book-" + i, author));
        }
        jdbcTemplate.update("delete from perflens_control");
        reports.clear();
    }

    @Test
    void dataSourceKeepsItsTypeAfterInstrumentation() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class)
                .isInstanceOf(PerfLensDataSourceBeanPostProcessor.Instrumented.class);
    }

    @Test
    void lazyCollectionLoopIsReportedAsNPlusOne() throws Exception {
        mockMvc.perform(get("/authors/lazy")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.boundary()).isEqualTo(TraceBoundary.HTTP_REQUEST);
        assertThat(report.route()).isEqualTo("/authors/lazy");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.sqlEvents()).hasSize(6);
        assertThat(report.hibernateEvents()).filteredOn(e -> e.eventType() == HibernateEventType.COLLECTION_INIT)
                .hasSize(5);
        assertThat(report.findingsOfType(NPlusOneAnalyzer.TYPE)).singleElement()
                .satisfies(finding -> assertThat(finding.evidence()).contains("5 executions", "Author.books x5"));
        assertThat(report.entityLoads()).containsEntry(Author.class.getName(), 5)
                .containsEntry(Book.class.getName(), 5);
        assertThat(report.resources().allocatedBytes()).isPositive();
        assertThat(report.resources().cpuNanos()).isPositive();
    }

    @Test
    void asyncWorkJoinsTheRequestTrace() throws Exception {
        mockMvc.perform(get("/plates/parallel")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.sqlEvents()).hasSize(3)
                .allSatisfy(event -> assertThat(event.sql()).contains("from book where id <> ?"));
        assertThat(report.connections()).hasSize(3);
        assertThat(report.transactions()).hasSize(3);
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(ConnectionPoolAnalyzer.TYPE);
            assertThat(finding.message()).contains("held 3 connections at once", "parallel work");
        });
    }

    @Test
    void fetchJoinRunsOneQueryWithNoFindings() throws Exception {
        mockMvc.perform(get("/authors/fetch-join")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.label()).isEqualTo("Authors with fetch join");
        assertThat(report.sqlEvents()).hasSize(1);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void sameQueryWithSameParametersIsReportedAsDuplicate() throws Exception {
        mockMvc.perform(get("/authors/count-twice")).andExpect(status().isOk());

        assertThat(reports.only().findings()).extracting(TraceFinding::type)
                .containsExactly(DuplicateQueryAnalyzer.TYPE);
    }

    @Test
    void transactionHeldOpenAroundNonSqlWorkIsReported() throws Exception {
        mockMvc.perform(get("/slow-tx")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.transactions()).singleElement().satisfies(tx -> {
            assertThat(tx.committed()).isTrue();
            assertThat(tx.statementCount()).isEqualTo(1);
            assertThat(tx.durationMillis()).isGreaterThanOrEqualTo(80);
        });
        assertThat(report.findingsOfType(SlowTransactionAnalyzer.TYPE)).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("Move remote calls"));
    }

    @Test
    void requiresNewInsideATransactionHoldsTwoConnections() throws Exception {
        mockMvc.perform(get("/nested-tx")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.transactions()).hasSize(2);
        assertThat(report.connections()).hasSize(2);
        assertThat(report.connections().get(1).heldByTrace()).isEqualTo(2);
        assertThat(report.connections().get(0).poolMax()).isEqualTo(10);
        assertThat(report.findingsOfType(ConnectionPoolAnalyzer.TYPE))
                .anySatisfy(finding -> assertThat(finding.message()).contains("held 2 connections"));
    }

    @Test
    void classLevelAnnotationOpensATraceOutsideMvc() throws Exception {
        mockMvc.perform(get("/job")).andExpect(status().isOk());

        TraceReport report = reports.only();
        assertThat(report.targetKey()).isEqualTo("nightly-job");
        assertThat(report.boundary()).isEqualTo(TraceBoundary.SERVICE_METHOD);
        assertThat(report.sqlEvents()).hasSize(1);
    }

    @Test
    void keysAreOnlyTracedWhenEnabled() throws Exception {
        mockMvc.perform(get("/not-enabled")).andExpect(status().isOk());
        mockMvc.perform(get("/plain")).andExpect(status().isOk());

        assertThat(reports.all()).isEmpty();
    }

    @Test
    void controlTableEnablesKeysAtRuntime() throws Exception {
        mockMvc.perform(get("/db-enabled")).andExpect(status().isOk());
        assertThat(reports.all()).isEmpty();

        jdbcTemplate.update("insert into perflens_control(target_key, enabled) values ('db-enabled', true)");
        mockMvc.perform(get("/db-enabled")).andExpect(status().isOk());
        assertThat(reports.only().targetKey()).isEqualTo("db-enabled");

        reports.clear();
        jdbcTemplate.update("update perflens_control set enabled_until = ? where target_key = 'db-enabled'",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        mockMvc.perform(get("/db-enabled")).andExpect(status().isOk());
        assertThat(reports.all()).isEmpty();
    }

    @Test
    void reportsAreStoredAndRenderedByTheViewer() throws Exception {
        mockMvc.perform(get("/authors/lazy")).andExpect(status().isOk());
        String traceId = reports.only().traceId();

        assertThat(store.findTrace(traceId)).hasValueSatisfying(trace -> {
            assertThat(trace.sqlCount()).isEqualTo(6);
            assertThat(trace.maxSeverity()).isEqualTo("WARNING");
        });
        String list = mockMvc.perform(get("/perflens/reports")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get("/perflens/reports/" + traceId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(list).contains("authors-lazy", "reports/" + traceId);
        assertThat(detail).contains("N_PLUS_ONE", "Author.books", "COLLECTION_INIT", "ENTITY_LOAD", "loaded 5",
                "Heap allocated");
        assertThat(reports.all()).hasSize(1);
    }

    @Test
    void retentionDeletesOldTraces() throws Exception {
        mockMvc.perform(get("/authors/fetch-join")).andExpect(status().isOk());

        int deleted = store.deleteStartedBefore(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from perflens_sql_event", Integer.class)).isZero();
    }
}
