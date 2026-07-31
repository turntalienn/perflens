package io.perflens.autoconfigure;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the PerfLens MVC interceptor with Spring MVC.
 */
public final class PerfLensWebMvcConfigurer implements WebMvcConfigurer {

    private final PerfLensHandlerInterceptor interceptor;

    public PerfLensWebMvcConfigurer(PerfLensHandlerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
