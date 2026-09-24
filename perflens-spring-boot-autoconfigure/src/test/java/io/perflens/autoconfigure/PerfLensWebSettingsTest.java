package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.perflens.autoconfigure.app.LibraryApplication;
import io.perflens.autoconfigure.app.LibraryApplication.CollectedReports;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = LibraryApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:perflens_web;DB_CLOSE_DELAY=-1",
        "perflens.async-reporting=false",
        "perflens.trace-all-requests=true",
        "perflens.storage.enabled=true",
        "perflens.storage.initialize-schema=true",
        "perflens.viewer.enabled=true"
})
@AutoConfigureMockMvc
class PerfLensWebSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CollectedReports reports;

    @Test
    void viewerRequiresTheConfiguredRole() throws Exception {
        mockMvc.perform(get("/perflens/reports")).andExpect(status().isForbidden());
        mockMvc.perform(get("/perflens/reports").with(request -> {
            request.addUserRole("PERFLENS_ADMIN");
            return request;
        })).andExpect(status().isOk());
    }

    @Test
    void traceAllRequestsCoversUnannotatedHandlersButNotTheViewer() throws Exception {
        reports.clear();
        mockMvc.perform(get("/plain")).andExpect(status().isOk());
        mockMvc.perform(get("/perflens/reports")).andExpect(status().isForbidden());

        assertThat(reports.only().targetKey()).isEqualTo("GET /plain");
    }
}
