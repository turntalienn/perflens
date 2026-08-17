package io.perflens.core.context;

/**
 * JVM resources used while a trace was open. Values are {@code -1} when the JVM
 * cannot measure them.
 *
 * <p>Allocation and CPU are per thread: the request thread plus any worker
 * threads that ran tasks wrapped with {@link PerfLensTraceContext#wrap(Runnable)}.
 * GC numbers are JVM-wide, so they show collections that overlapped the request,
 * not collections the request caused on its own.</p>
 *
 * @param allocatedBytes heap bytes allocated by the traced threads
 * @param cpuNanos CPU time used by the traced threads
 * @param gcCount garbage collections that ran during the trace (stop-the-world collectors only)
 * @param gcMillis time spent in those collections
 */
public record ResourceUsage(long allocatedBytes, long cpuNanos, long gcCount, long gcMillis) {

    /** Usage when nothing could be measured. */
    public static final ResourceUsage UNKNOWN = new ResourceUsage(-1, -1, -1, -1);
}
