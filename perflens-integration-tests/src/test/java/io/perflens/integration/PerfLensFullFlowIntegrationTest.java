package io.perflens.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.perflens.autoconfigure.PerfLensTraceStore;
import io.perflens.core.annotation.PerfLensTrace;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = PerfLensFullFlowIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:perflens_full_flow;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.main.banner-mode=off",
        "perflens.storage.initialize-schema=true",
        "perflens.viewer.enabled=true",
        "perflens.viewer.require-role=false"
})
class PerfLensFullFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DemoOrderRepository orderRepository;

    @Autowired
    private PerfLensTraceStore traceStore;

    @BeforeEach
    void cleanTablesAndSeedBusinessData() {
        jdbcTemplate.update("delete from perflens_sql_event");
        jdbcTemplate.update("delete from perflens_hibernate_event");
        jdbcTemplate.update("delete from perflens_finding");
        jdbcTemplate.update("delete from perflens_span");
        jdbcTemplate.update("delete from perflens_trace");
        jdbcTemplate.update("delete from perflens_control");

        orderRepository.deleteAll();
        orderRepository.save(new DemoOrder("A-100"));
    }

    @Test
    void annotatedControllerCallPersistsIsolatedTraceWithSqlHibernateEventsAndFindings() throws Exception {
        enableDemoOrders();

        mockMvc.perform(get("/demo/orders"))
                .andExpect(status().isOk());

        Integer traces = jdbcTemplate.queryForObject("select count(*) from perflens_trace where target_key = ?",
                Integer.class, "demo-orders");
        Integer sqlEvents = jdbcTemplate.queryForObject("select count(*) from perflens_sql_event", Integer.class);
        Integer hibernateEvents = jdbcTemplate.queryForObject("select count(*) from perflens_hibernate_event", Integer.class);

        assertThat(traces).isEqualTo(1);
        assertThat(sqlEvents).isGreaterThanOrEqualTo(1);
        assertThat(hibernateEvents).isGreaterThanOrEqualTo(1);
    }

    @Test
    void viewerRendersSavedTraceAsHtml() throws Exception {
        enableDemoOrders();

        mockMvc.perform(get("/demo/orders"))
                .andExpect(status().isOk());

        String traceId = jdbcTemplate.queryForObject("select trace_id from perflens_trace limit 1", String.class);

        String html = mockMvc.perform(get("/perflens/reports/" + traceId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("PerfLens Trace Report", "demo-orders", "SQL Events", "Hibernate Events",
                "Findings");
    }

    @Test
    void reportListRendersSavedTraceSummary() throws Exception {
        enableDemoOrders();

        mockMvc.perform(get("/demo/orders"))
                .andExpect(status().isOk());

        String html = mockMvc.perform(get("/perflens/reports"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("PerfLens Reports", "demo-orders");
    }

    @Test
    void classLevelServiceAnnotationCanOpenMethodRootTrace() throws Exception {
        enableTarget("class-level-service");

        mockMvc.perform(get("/demo/class-level"))
                .andExpect(status().isOk());

        Integer traces = jdbcTemplate.queryForObject("select count(*) from perflens_trace where target_key = ?",
                Integer.class, "class-level-service");

        assertThat(traces).isEqualTo(1);
    }

    @Test
    void disabledControlRowDoesNotPersistTrace() throws Exception {
        mockMvc.perform(get("/demo/orders"))
                .andExpect(status().isOk());

        Integer traces = jdbcTemplate.queryForObject("select count(*) from perflens_trace", Integer.class);

        assertThat(traces).isZero();
    }

    @Test
    void retentionDeletesExpiredTraceRows() throws Exception {
        enableDemoOrders();
        mockMvc.perform(get("/demo/orders"))
                .andExpect(status().isOk());
        jdbcTemplate.update("update perflens_trace set ended_at = timestamp '2026-01-01 00:00:00'");

        int deleted = traceStore.deleteOlderThan(Duration.ofDays(1));

        Integer traces = jdbcTemplate.queryForObject("select count(*) from perflens_trace", Integer.class);
        assertThat(deleted).isEqualTo(1);
        assertThat(traces).isZero();
    }

    private void enableDemoOrders() {
        enableTarget("demo-orders");
    }

    private void enableTarget(String targetKey) {
        jdbcTemplate.update("insert into perflens_control(target_key, enabled, capture_parameters) values (?, true, false)",
                targetKey);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = DemoOrder.class)
    @EnableJpaRepositories(basePackageClasses = PerfLensFullFlowIntegrationTest.class, considerNestedRepositories = true)
    @Import({DemoOrderService.class, ClassLevelDemoService.class})
    static class TestApplication {

        @Bean
        DemoController demoController(DemoOrderService service, ClassLevelDemoService classLevelService) {
            return new DemoController(service, classLevelService);
        }
    }

    @RestController
    static class DemoController {

        private final DemoOrderService service;
        private final ClassLevelDemoService classLevelService;

        DemoController(DemoOrderService service, ClassLevelDemoService classLevelService) {
            this.service = service;
            this.classLevelService = classLevelService;
        }

        @GetMapping("/demo/orders")
        @PerfLensTrace(value = "demo-orders", label = "Demo orders")
        String loadOrders() {
            return service.loadOrders();
        }

        @GetMapping("/demo/class-level")
        String classLevel() {
            return classLevelService.load();
        }
    }

    @Service
    static class DemoOrderService {

        private final DemoOrderRepository repository;

        DemoOrderService(DemoOrderRepository repository) {
            this.repository = repository;
        }

        @Transactional(readOnly = true)
        @PerfLensTrace(value = "demo-order-service", label = "Demo order service")
        public String loadOrders() {
            return repository.findAll().stream()
                    .map(DemoOrder::getOrderNumber)
                    .findFirst()
                    .orElse("none");
        }
    }

    @Service
    @PerfLensTrace(value = "class-level-service", label = "Class level service")
    public static class ClassLevelDemoService {

        public String load() {
            return "class-level-ok";
        }
    }

    interface DemoOrderRepository extends JpaRepository<DemoOrder, Long> {
    }

    @Entity
    @Table(name = "demo_order")
    static class DemoOrder {

        @Id
        @GeneratedValue
        private Long id;

        private String orderNumber;

        protected DemoOrder() {
        }

        DemoOrder(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        String getOrderNumber() {
            return orderNumber;
        }
    }
}
