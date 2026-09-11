package io.perflens.autoconfigure;

import io.perflens.core.annotation.PerfLensTrace;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.TraceBoundary;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;

/**
 * Traces {@code @PerfLensTrace} methods on Spring beans.
 *
 * <p>Inside an open trace the method becomes a child span. Outside one, it opens
 * its own trace if its key is enabled, which covers scheduled jobs and message
 * listeners. The aspect is ordered ahead of {@code @Transactional}, so a
 * method-level trace includes the commit.</p>
 *
 * <p>Normal Spring proxy rules apply: calls from one method to another on the same
 * bean bypass the proxy and are not traced separately.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class PerfLensTraceAspect {

    private final PerfLensTracer tracer;

    PerfLensTraceAspect(PerfLensTracer tracer) {
        this.tracer = tracer;
    }

    @Around("@annotation(io.perflens.core.annotation.PerfLensTrace) "
            + "|| @within(io.perflens.core.annotation.PerfLensTrace)")
    Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        PerfLensTrace annotation = annotationOf(joinPoint);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        if (PerfLensTraceContext.isActive()) {
            return proceedInSpan(joinPoint, annotation);
        }
        if (!tracer.startRoot(annotation, TraceBoundary.SERVICE_METHOD, null, null)) {
            return joinPoint.proceed();
        }
        Throwable failure = null;
        try {
            return joinPoint.proceed();
        }
        catch (Throwable ex) {
            failure = ex;
            throw ex;
        }
        finally {
            tracer.finishRoot(null, failure);
        }
    }

    /** Opens a child span, unless this is the controller method whose request trace is already open. */
    private Object proceedInSpan(ProceedingJoinPoint joinPoint, PerfLensTrace annotation) throws Throwable {
        if (PerfLensTraceContext.currentSpanKey().filter(annotation.value()::equals).isPresent()) {
            return joinPoint.proceed();
        }
        tracer.startSpan(annotation);
        try {
            return joinPoint.proceed();
        }
        finally {
            tracer.finishSpan();
        }
    }

    private static PerfLensTrace annotationOf(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return null;
        }
        Class<?> targetClass = joinPoint.getTarget() == null
                ? signature.getDeclaringType()
                : AopUtils.getTargetClass(joinPoint.getTarget());
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        PerfLensTrace annotation = AnnotatedElementUtils.findMergedAnnotation(method, PerfLensTrace.class);
        return annotation != null ? annotation
                : AnnotatedElementUtils.findMergedAnnotation(targetClass, PerfLensTrace.class);
    }
}
