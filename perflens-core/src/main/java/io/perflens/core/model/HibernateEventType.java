package io.perflens.core.model;

/**
 * Hibernate lifecycle event categories captured by PerfLens.
 */
public enum HibernateEventType {

    /**
     * Hibernate finished loading an entity instance into the persistence context.
     */
    ENTITY_LOAD,

    /**
     * Hibernate initialized a persistent collection, often because lazy traversal
     * touched an association.
     */
    COLLECTION_INIT,

    /**
     * Hibernate flushed the persistence context.
     */
    FLUSH,

    /**
     * Hibernate performed an automatic flush before query execution.
     */
    AUTO_FLUSH,

    /**
     * Hibernate observed an entity insert.
     */
    ENTITY_INSERT,

    /**
     * Hibernate observed an entity update.
     */
    ENTITY_UPDATE,

    /**
     * Hibernate observed an entity delete.
     */
    ENTITY_DELETE
}
