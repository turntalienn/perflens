package io.perflens.autoconfigure;

/**
 * Decides whether a {@code @PerfLensTrace} key is traced right now.
 *
 * <p>Called once per root boundary, on the request thread, so implementations
 * must be fast and thread-safe. Declare a bean of this type to replace the default
 * (for example to drive tracing from a feature-flag service).</p>
 */
@FunctionalInterface
public interface TraceControl {

    /**
     * @param targetKey key from {@code @PerfLensTrace}
     * @return whether to open a trace for it
     */
    boolean isEnabled(String targetKey);
}
