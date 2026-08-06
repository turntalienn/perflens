package io.perflens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.perflens.core.PerfLensCore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PerfLensStarterIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void starterBringsCoreOntoTheClasspath() {
        assertThat(PerfLensCore.COMPONENT_NAME).isEqualTo("perflens-core");
    }

    @Test
    void springContextCanStartWithStarterOnTheClasspath() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }
}
