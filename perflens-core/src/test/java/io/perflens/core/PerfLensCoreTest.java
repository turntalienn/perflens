package io.perflens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PerfLensCoreTest {

    @Test
    void exposesCoreComponentName() {
        assertEquals("perflens-core", PerfLensCore.COMPONENT_NAME);
    }
}
