package io.perflens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.perflens.core.PerfLens;
import io.perflens.core.context.TraceReport;
import io.perflens.core.findings.NPlusOneAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shows how to guard query counts in an application's own test suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void lazyEndpointHasAnNPlusOne() throws Exception {
        TraceReport report = PerfLens.capture("authors",
                () -> mockMvc.perform(get("/authors")).andExpect(status().isOk())).report();

        assertThat(report.sqlEvents()).hasSize(6);
        assertThat(report.findingsOfType(NPlusOneAnalyzer.TYPE)).hasSize(1);
    }

    @Test
    void fixedEndpointUsesOneQuery() throws Exception {
        TraceReport report = PerfLens.capture("authors-fixed",
                () -> mockMvc.perform(get("/authors/fixed")).andExpect(status().isOk())).report();

        assertThat(report.sqlEvents()).hasSize(1);
        assertThat(report.findings()).isEmpty();
    }
}
