package io.perflens.autoconfigure;

import io.perflens.core.annotation.PerfLensTrace;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.context.TraceReport;
import io.perflens.core.model.TraceBoundary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Spring MVC interceptor that keeps controller traces open through request
 * completion.
 *
 * <p>Closing in {@link #afterCompletion(HttpServletRequest, HttpServletResponse,
 * Object, Exception)} lets PerfLens include lazy loading that happens while the
 * response body is rendered, which a method-only aspect would miss.</p>
 */
public final class PerfLensHandlerInterceptor implements HandlerInterceptor {

    private static final String STARTED_ATTRIBUTE = PerfLensHandlerInterceptor.class.getName() + ".started";

    private final PerfLensControlService controlService;
    private final PerfLensTraceStore traceStore;
    private final Clock clock;

    public PerfLensHandlerInterceptor(PerfLensControlService controlService, PerfLensTraceStore traceStore,
            Clock clock) {
        this.controlService = controlService;
        this.traceStore = traceStore;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        PerfLensTrace trace = traceAnnotation(handlerMethod);
        if (trace == null || !controlService.isEnabled(trace.value())) {
            return true;
        }
        String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        PerfLensTraceContext.startRoot(trace.value(), label(trace), TraceBoundary.HTTP_REQUEST, route,
                request.getMethod(), clock);
        request.setAttribute(STARTED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        if (!Boolean.TRUE.equals(request.getAttribute(STARTED_ATTRIBUTE))) {
            return;
        }
        String error = exception == null ? null : exception.getClass().getName();
        TraceReport report = PerfLensTraceContext.closeRoot(response.getStatus(), error, clock);
        traceStore.save(report);
    }

    private PerfLensTrace traceAnnotation(HandlerMethod handlerMethod) {
        PerfLensTrace methodAnnotation = handlerMethod.getMethodAnnotation(PerfLensTrace.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return handlerMethod.getBeanType().getAnnotation(PerfLensTrace.class);
    }

    private String label(PerfLensTrace trace) {
        return trace.label().isBlank() ? trace.value() : trace.label();
    }
}
