package com.neocat;

import com.neocat.common.queue.QueueFactory;
import com.neocat.common.queue.impl.BoundedDropQueueFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 队列工厂与启动配置校验装配；时间能力由公共静态入口提供。
 *
 * <p>动态参数由按用途拆分的配置类持有，本类不复制动态参数。
 */
@Configuration
public class RuntimeConfiguration {

    /**
     * 启动配置校验：Apollo 的属性源由原生处理器在刷新期安装，本处理器是普通
     * BeanFactoryPostProcessor，因此在其之后、任何业务 Bean 之前执行，配置缺失或非法即拒绝启动。
     */
    @Bean
    public static BeanFactoryPostProcessor apolloConfigGuard(ConfigurableEnvironment environment) {
        return beanFactory -> ApolloConfigGuard.validate(environment);
    }

    @Bean
    public QueueFactory queueFactory() {
        return new BoundedDropQueueFactory();
    }
}
