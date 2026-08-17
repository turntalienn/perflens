package io.perflens.core.context;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Reads per-thread allocation and CPU counters and JVM-wide GC counters.
 *
 * <p>Uses {@code com.sun.management.ThreadMXBean} (HotSpot and OpenJ9). On JVMs
 * without it, every reading is {@code -1}. Concurrent GC phases ("Cycles",
 * "Concurrent" beans) are skipped so GC time approximates pause time.</p>
 */
final class ResourceSampler {

    private static final List<GarbageCollectorMXBean> PAUSING_COLLECTORS = ManagementFactory
            .getGarbageCollectorMXBeans().stream()
            .filter(bean -> !bean.getName().contains("Cycles") && !bean.getName().contains("Concurrent"))
            .toList();

    private ResourceSampler() {
    }

    static long allocatedBytes() {
        return Threads.ALLOCATION ? Threads.BEAN.getCurrentThreadAllocatedBytes() : -1;
    }

    static long cpuNanos() {
        return Threads.CPU ? Threads.BEAN.getCurrentThreadCpuTime() : -1;
    }

    static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : PAUSING_COLLECTORS) {
            total += Math.max(0, bean.getCollectionCount());
        }
        return total;
    }

    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : PAUSING_COLLECTORS) {
            total += Math.max(0, bean.getCollectionTime());
        }
        return total;
    }

    static long delta(long start, long end) {
        return start < 0 || end < 0 ? -1 : end - start;
    }

    /** Loaded only when first used, so a JVM without com.sun.management fails softly. */
    private static final class Threads {

        private static final com.sun.management.ThreadMXBean BEAN;
        private static final boolean ALLOCATION;
        private static final boolean CPU;

        static {
            com.sun.management.ThreadMXBean bean = null;
            try {
                if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun) {
                    bean = sun;
                }
            }
            catch (LinkageError ex) {
                bean = null;
            }
            BEAN = bean;
            ALLOCATION = bean != null && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled();
            CPU = bean != null && bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        }
    }
}
