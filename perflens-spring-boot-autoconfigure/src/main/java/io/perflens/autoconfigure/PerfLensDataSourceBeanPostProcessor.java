package io.perflens.autoconfigure;

import java.lang.reflect.Modifier;
import javax.sql.DataSource;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@link DataSource} bean so its connections are instrumented.
 *
 * <p>The wrapper is a class-based proxy where possible, so the bean keeps its
 * concrete type: code that injects {@code HikariDataSource}, Actuator pool
 * metrics and Hibernate's pool detection keep working. Final DataSource classes
 * fall back to an interface proxy.</p>
 */
public final class PerfLensDataSourceBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource) || bean instanceof Instrumented) {
            return bean;
        }
        ProxyFactory factory = new ProxyFactory(bean);
        factory.addInterface(Instrumented.class);
        factory.addAdvice(new JdbcInstrumentation.DataSourceInterceptor(PoolProbe.forDataSource(dataSource)));
        factory.setProxyTargetClass(!Modifier.isFinal(bean.getClass().getModifiers()));
        try {
            return factory.getProxy(bean.getClass().getClassLoader());
        }
        catch (AopConfigException ex) {
            factory.setProxyTargetClass(false);
            return factory.getProxy(bean.getClass().getClassLoader());
        }
    }

    /** Marker added to instrumented DataSources so they are never wrapped twice. */
    public interface Instrumented {
    }
}
