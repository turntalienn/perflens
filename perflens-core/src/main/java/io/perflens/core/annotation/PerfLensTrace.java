package io.perflens.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring controller or service method as eligible for PerfLens tracing.
 *
 * <p>The annotation itself does not force tracing to run. PerfLens still checks
 * its runtime control source before opening a root trace. This keeps production
 * adoption safe because teams can annotate important APIs during development and
 * enable individual targets later through operational controls.</p>
 *
 * <p>The {@link #value()} is intentionally explicit instead of being inferred
 * from a route or method name. Stable keys make it possible to rename Java
 * methods or move endpoints without losing report history.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PerfLensTrace {

    /**
     * Stable operational key used by PerfLens control rows and reports.
     *
     * @return a non-empty target key such as {@code order-detail}
     */
    String value();

    /**
     * Optional human-readable label shown in reports.
     *
     * @return display label, or an empty string when the key should be shown
     */
    String label() default "";
}
