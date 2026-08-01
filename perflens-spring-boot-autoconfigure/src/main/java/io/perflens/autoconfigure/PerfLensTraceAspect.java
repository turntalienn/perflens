package io.perflens.autoconfigure;

import io.perflens.core.annotation.PerfLensTrace;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.TraceBoundary;
import java.lang.reflect.Method;
import java.time.Clock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Spring AOP adapter for service-method PerfLens spans.
 *
 * <p>When an annotated service method runs inside a controller trace, the method
 * becomes a child span. When it runs without a current trace, PerfLens checks the
 * DB control row and may open a method-root trace.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class PerfLensTraceAspect {

    private final PerfLensControlService controlService;
    private final PerfLensTraceStore traceStore;
    private final Clock clock;

    public PerfLensTraceAspect(PerfLensControlService controlService, PerfLensTraceStore traceStore, Clock clock) {
        this.controlService = controlService;
        this.traceStore = traceStore;
        this.clock = clock;
    }

    @Around("@annotation(io.perflens.core.annotation.PerfLensTrace) || "
            + "@within(io.perflens.core.annotation.PerfLensTrace)")
    public Object traceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        PerfLensTrace trace = traceAnnotation(joinPoint);
        if (trace == null) {
            return joinPoint.proceed();
        }
        if (PerfLensTraceContext.currentTrace().isPresent()) {
            PerfLensTraceContext.startChildSpan(trace.value(), label(trace), TraceBoundary.SERVICE_METHOD, clock);
            try {
                return joinPoint.proceed();
            }
            finally {
                PerfLensTraceContext.closeCurrentSpan(clock);
            }
        }
        if (!controlService.isEnabled(trace.value())) {
            return joinPoint.proceed();
        }
        PerfLensTraceContext.startRoot(trace.value(), label(trace), TraceBoundary.SERVICE_METHOD, null, null, clock);
        try {
            Object result = joinPoint.proceed();
            TraceReport report = PerfLensTraceContext.closeRoot(null, null, clock);
            traceStore.save(report);
            return result;
        }
        catch (Throwable ex) {
            TraceReport report = PerfLensTraceContext.closeRoot(null, ex.getClass().getName(), clock);
            traceStore.save(report);
            throw ex;
        }
    }

    private String label(PerfLensTrace trace) {
        return trace.label().isBlank() ? trace.value() : trace.label();
    }

    private PerfLensTrace traceAnnotation(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return null;
        }
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null
                ? signature.getDeclaringType()
                : AopUtils.getTargetClass(joinPoint.getTarget());
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        PerfLensTrace methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod,
                PerfLensTrace.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(targetClass, PerfLensTrace.class);
    }
}
