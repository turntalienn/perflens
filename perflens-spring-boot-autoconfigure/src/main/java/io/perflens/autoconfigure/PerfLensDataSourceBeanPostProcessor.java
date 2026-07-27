package io.perflens.autoconfigure;

import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps the primary DataSource so SQL execution can be timed per trace.
 */
public final class PerfLensDataSourceBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof PerfLensDataSource)) {
            return new PerfLensDataSource(dataSource);
        }
        return bean;
    }
}
