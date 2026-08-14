package io.perflens.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or Spring bean method (or every method of a class) as a traced boundary.
 *
 * <p>On a controller handler the trace covers the whole request, including view
 * rendering and lazy loading during serialization. On any other Spring bean the
 * method becomes a child span when a trace is already open, or opens its own
 * trace otherwise.</p>
 *
 * <p>The annotation alone does not switch tracing on. The key must also be enabled,
 * either through {@code perflens.control.targets} or the {@code perflens_control}
 * table, so annotations can ship to every environment and be turned on where needed.</p>
 *
 * <pre>{@code
 * @GetMapping("/orders/{id}")
 * @PerfLensTrace("order-detail")
 * OrderDto order(@PathVariable long id) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PerfLensTrace {

    /**
     * Stable key used to enable the trace and to group reports. Kept explicit so
     * renaming a method or moving a route does not break report history.
     *
     * @return target key such as {@code order-detail}
     */
    String value();

    /**
     * Optional display name for reports; the key is shown when empty.
     *
     * @return display label
     */
    String label() default "";
}
