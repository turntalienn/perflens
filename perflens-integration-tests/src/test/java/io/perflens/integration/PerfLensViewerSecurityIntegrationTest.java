package io.perflens.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PerfLensViewerSecurityIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:perflens_viewer_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.main.banner-mode=off",
        "perflens.storage.initialize-schema=true",
        "perflens.viewer.enabled=true",
        "perflens.viewer.require-role=true"
})
class PerfLensViewerSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTrace() {
        jdbcTemplate.update("delete from perflens_trace");
        jdbcTemplate.update("""
                insert into perflens_trace(trace_id, target_key, label, boundary, route, http_method, status_code,
                  error_message, started_at, ended_at, duration_ms, query_count, hibernate_event_count, finding_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "security-trace", "secure-target", "Secure target", "HTTP_REQUEST", "/secure", "GET", 200,
                null, Timestamp.from(Instant.parse("2026-05-31T08:00:00Z")),
                Timestamp.from(Instant.parse("2026-05-31T08:00:01Z")), 1000.0, 0, 0, 0);
    }

    @Test
    void viewerRequiresConfiguredRoleWhenRoleChecksAreEnabled() throws Exception {
        mockMvc.perform(get("/perflens/reports/security-trace"))
                .andExpect(status().isForbidden());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
