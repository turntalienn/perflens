package io.perflens.core.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class PerfLensTraceTest {

    @Test
    void exposesStableAnnotationKeyAndOptionalLabel() throws Exception {
        Method method = ExampleApi.class.getDeclaredMethod("loadOrder");

        PerfLensTrace annotation = method.getAnnotation(PerfLensTrace.class);

        assertThat(annotation.value()).isEqualTo("order-detail");
        assertThat(annotation.label()).isEqualTo("Order detail API");
    }

    private static final class ExampleApi {

        @PerfLensTrace(value = "order-detail", label = "Order detail API")
        void loadOrder() {
        }
    }
}
