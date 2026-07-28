package io.perflens.autoconfigure;

import java.util.List;
import java.util.Map;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;

/**
 * Supplies the PerfLens Hibernate integrator to Spring Boot's JPA bootstrap.
 */
public final class PerfLensHibernatePropertiesCustomizer implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.integrator_provider",
                (IntegratorProvider) () -> List.of(new PerfLensHibernateIntegrator()));
    }
}
