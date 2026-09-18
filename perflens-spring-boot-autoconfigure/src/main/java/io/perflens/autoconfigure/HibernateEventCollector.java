package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.HibernateEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.AutoFlushEvent;
import org.hibernate.event.spi.AutoFlushEventListener;
import org.hibernate.event.spi.EventType;
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
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Hibernate integrator and listener that records entity loads, lazy collection
 * initialization, flushes and entity writes into the current trace.
 */
final class HibernateEventCollector implements Integrator, PostLoadEventListener,
        InitializeCollectionEventListener, FlushEventListener, AutoFlushEventListener, PostInsertEventListener,
        PostUpdateEventListener, PostDeleteEventListener {

    private static final String INTEGRATOR_PROVIDER = "hibernate.integrator_provider";

    /**
     * Adds the collector to the Hibernate properties Spring Boot passes to JPA,
     * keeping any {@link IntegratorProvider} instance the application already set.
     *
     * @param hibernateProperties properties to customize
     */
    static void register(Map<String, Object> hibernateProperties) {
        Object existing = hibernateProperties.get(INTEGRATOR_PROVIDER);
        hibernateProperties.put(INTEGRATOR_PROVIDER, (IntegratorProvider) () -> {
            List<Integrator> integrators = new ArrayList<>();
            if (existing instanceof IntegratorProvider provider) {
                integrators.addAll(provider.getIntegrators());
            }
            integrators.add(new HibernateEventCollector());
            return integrators;
        });
    }

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
            SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_LOAD, this);
        registry.appendListeners(EventType.INIT_COLLECTION, this);
        registry.appendListeners(EventType.FLUSH, this);
        registry.appendListeners(EventType.AUTO_FLUSH, this);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry registry) {
        // Listeners are owned by Hibernate's registry; nothing to release.
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_LOAD,
                    event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
        }
    }

    @Override
    public void onInitializeCollection(InitializeCollectionEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            String role = event.getCollection() == null ? null : event.getCollection().getRole();
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.COLLECTION_INIT, null, role,
                    String.valueOf(event.getAffectedOwnerIdOrNull()));
        }
    }

    @Override
    public void onFlush(FlushEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.FLUSH, null, null,
                    "entities=" + event.getNumberOfEntitiesProcessed()
                            + ", collections=" + event.getNumberOfCollectionsProcessed());
        }
    }

    @Override
    public void onAutoFlush(AutoFlushEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.AUTO_FLUSH, null, null,
                    "required=" + event.isFlushRequired());
        }
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_INSERT,
                    event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
        }
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            int dirty = event.getDirtyProperties() == null ? 0 : event.getDirtyProperties().length;
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_UPDATE,
                    event.getPersister().getEntityName(), null, "dirtyProperties=" + dirty);
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        if (PerfLensTraceContext.isCollecting()) {
            PerfLensTraceContext.recordHibernateEvent(HibernateEventType.ENTITY_DELETE,
                    event.getPersister().getEntityName(), null, String.valueOf(event.getId()));
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}
