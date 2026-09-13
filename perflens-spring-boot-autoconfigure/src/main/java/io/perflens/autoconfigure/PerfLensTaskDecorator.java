package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Makes tasks submitted to Spring executors join the submitting thread's trace,
 * so SQL, connections, allocation and CPU of {@code @Async} methods and parallel
 * work count toward the request that started them.
 *
 * <p>Registered automatically unless the application has its own
 * {@link TaskDecorator}. In that case, compose the two:</p>
 *
 * <pre>{@code
 * @Bean
 * TaskDecorator taskDecorator() {
 *     return new PerfLensTaskDecorator(new MdcTaskDecorator());
 * }
 * }</pre>
 *
 * <p>For plain {@code ExecutorService}s or {@code CompletableFuture.supplyAsync},
 * wrap tasks with {@link PerfLensTraceContext#wrap(Runnable)}.</p>
 */
public final class PerfLensTaskDecorator implements TaskDecorator {

    private final TaskDecorator delegate;

    /** Decorator that only propagates the trace. */
    public PerfLensTaskDecorator() {
        this(runnable -> runnable);
    }

    /**
     * @param delegate decorator applied inside the trace propagation
     */
    public PerfLensTaskDecorator(TaskDecorator delegate) {
        this.delegate = delegate;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        return PerfLensTraceContext.wrap(delegate.decorate(runnable));
    }
}
