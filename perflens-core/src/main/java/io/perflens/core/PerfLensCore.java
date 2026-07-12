package io.perflens.core;

/**
 * Marker for the framework-neutral PerfLens core artifact.
 *
 * <p>The class gives tests and downstream tools a stable type to reference when
 * they need to assert that the core module is present without depending on a
 * specific runtime integration.</p>
 */
public final class PerfLensCore {

    /**
     * Human-readable component name used by sanity tests and diagnostics.
     */
    public static final String COMPONENT_NAME = "perflens-core";

    private PerfLensCore() {
    }
}
