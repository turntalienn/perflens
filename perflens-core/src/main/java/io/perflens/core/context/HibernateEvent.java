package io.perflens.core.context;

import io.perflens.core.model.HibernateEventType;

/**
 * One Hibernate lifecycle event captured under a trace.
 *
 * @param ordinal 1-based event order within the trace
 * @param spanId span that was active when Hibernate fired the event
 * @param eventType event category
 * @param entityName entity name, or {@code null} for collection and flush events
 * @param collectionRole collection role such as {@code com.acme.Order.lines}, or {@code null}
 * @param detail short free-form detail (entity id, flush counts, dirty property count)
 */
public record HibernateEvent(
        int ordinal,
        String spanId,
        HibernateEventType eventType,
        String entityName,
        String collectionRole,
        String detail) {
}
