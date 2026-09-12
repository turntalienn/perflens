package io.perflens.autoconfigure;

import io.perflens.core.annotation.PerfLensTrace;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.TraceBoundary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Opens a trace before an annotated controller handler runs and closes it after
 * the request completes, so SQL issued while rendering the response (lazy loading
 * during JSON serialization, open-session-in-view) is included.
 *
 * <p>Requests that go async ({@code Callable}, {@code DeferredResult}) are not
 * traced: their trace is discarded when the request thread is released.</p>
 */
final class PerfLensHandlerInterceptor implements AsyncHandlerInterceptor {

    private static final String STARTED = PerfLensHandlerInterceptor.class.getName() + ".started";

    private final PerfLensTracer tracer;
    private final boolean traceAllRequests;

    PerfLensHandlerInterceptor(PerfLensTracer tracer, boolean traceAllRequests) {
        this.tracer = tracer;
        this.traceAllRequests = traceAllRequests;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? request.getRequestURI() : pattern.toString();
        PerfLensTrace annotation = annotationOf(handlerMethod);
        boolean started = annotation != null
                ? tracer.startRoot(annotation, TraceBoundary.HTTP_REQUEST, route, request.getMethod())
                : traceAllRequests && tracer.startRequest(request.getMethod() + " " + route, route, request.getMethod());
        if (started) {
            request.setAttribute(STARTED, Boolean.TRUE);
        }
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getAttribute(STARTED) != null) {
            request.removeAttribute(STARTED);
            PerfLensTraceContext.discard();
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        if (request.getAttribute(STARTED) == null) {
            return;
        }
        request.removeAttribute(STARTED);
        Throwable error = exception != null ? exception
                : (Throwable) request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        tracer.finishRoot(response.getStatus(), error);
    }

    private static PerfLensTrace annotationOf(HandlerMethod handlerMethod) {
        PerfLensTrace annotation = handlerMethod.getMethodAnnotation(PerfLensTrace.class);
        return annotation != null ? annotation
                : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PerfLensTrace.class);
    }
}
