package io.perflens.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PerfLensAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PerfLensAutoConfiguration.class));

    @Test
    void autoConfigurationLoadsWithoutCreatingRuntimeTracingBeans() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }
}
