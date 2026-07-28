package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.HibernateEventType;
import org.hibernate.event.spi.AutoFlushEvent;
import org.hibernate.event.spi.AutoFlushEventListener;
import org.hibernate.event.spi.FlushEvent;
import org.hibernate.event.spi.FlushEventListener;
import org.hibernate.event.spi.InitializeCollectionEvent;
import org.hibernate.event.spi.InitializeCollectionEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

/**
 * Hibernate event listener that converts ORM lifecycle callbacks into PerfLens
 * trace evidence.
 */
public final class PerfLensHibernateEventListener implements PostLoadEventListener, InitializeCollectionEventListener,
        FlushEventListener, AutoFlushEventListener, PostInsertEventListener, PostUpdateEventListener,
        PostDeleteEventListener {

    @Override
    public void onPostLoad(PostLoadEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD,
                event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
    }

    @Override
    public void onInitializeCollection(InitializeCollectionEvent event) {
        String role = event.getCollection() == null ? null : event.getCollection().getRole();
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.COLLECTION_INIT, null, role, "collection init");
    }

    @Override
    public void onFlush(FlushEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.FLUSH, null, null,
                "entities=" + event.getNumberOfEntitiesProcessed()
                        + ", collections=" + event.getNumberOfCollectionsProcessed());
    }

    @Override
    public void onAutoFlush(AutoFlushEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.AUTO_FLUSH, null, null,
                "required=" + event.isFlushRequired());
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_INSERT,
                event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_UPDATE,
                event.getPersister().getEntityName(), null, "dirtyProperties=" + event.getDirtyProperties().length);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_DELETE,
                event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}
