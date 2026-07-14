package io.perflens.core.model;

/**
 * Describes the kind of application boundary represented by a trace span.
 */
public enum TraceBoundary {

    /**
     * A Spring MVC request that starts before controller execution and ends after
     * request completion.
     */
    HTTP_REQUEST,

    /**
     * A Spring bean method intercepted through Spring AOP.
     */
    SERVICE_METHOD
}
