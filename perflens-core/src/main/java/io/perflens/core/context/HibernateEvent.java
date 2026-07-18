package io.perflens.core.context;

import io.perflens.core.model.HibernateEventType;

/**
 * Immutable Hibernate lifecycle evidence captured under an active trace.
 *
 * @param ordinal per-trace event order
 * @param spanId span active when Hibernate emitted the event
 * @param eventType event category
 * @param entityName entity name when available
 * @param collectionRole collection role when available
 * @param detail human-readable event detail
 */
public record HibernateEvent(
        int ordinal,
        String spanId,
        HibernateEventType eventType,
        String entityName,
        String collectionRole,
        String detail) {
}
