/**
 * Thread-bound trace context and the immutable events and reports it produces.
 *
 * <p>Collectors (the JDBC proxy, the Hibernate listener) call the static
 * {@code record*} methods on {@link io.perflens.core.context.PerfLensTraceContext}.
 * Those calls are no-ops unless a trace is open on the current thread, so the
 * collectors can stay installed permanently.</p>
 */
package io.perflens.core.context;
