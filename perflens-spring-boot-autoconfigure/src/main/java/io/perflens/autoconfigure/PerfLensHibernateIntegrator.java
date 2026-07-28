package io.perflens.autoconfigure;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Hibernate integrator that registers the PerfLens event listener.
 */
public final class PerfLensHibernateIntegrator implements Integrator {

    private final PerfLensHibernateEventListener listener = new PerfLensHibernateEventListener();

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
            SessionFactoryServiceRegistry serviceRegistry) {
        EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_LOAD, listener);
        registry.appendListeners(EventType.INIT_COLLECTION, listener);
        registry.appendListeners(EventType.FLUSH, listener);
        registry.appendListeners(EventType.AUTO_FLUSH, listener);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
        // Hibernate owns listener lifecycle; there is no external resource to close.
    }
}
