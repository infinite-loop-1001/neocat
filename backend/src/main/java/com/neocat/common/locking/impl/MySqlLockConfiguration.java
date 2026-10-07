package com.neocat.common.locking.impl;

import com.neocat.common.locking.MySqlLocked;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 必要的代理工厂，和无数据库的 Apollo 参数校验配置分离。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
public class MySqlLockConfiguration {
    @Bean
    @org.springframework.context.annotation.Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor mysqlLockAdvisor(MySqlLockAspect interceptor) {
        var pointcut = new AnnotationMatchingPointcut(null, MySqlLocked.class, true);
        var advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }
}
