package com.neocat.common.locking.impl;

import com.neocat.common.locking.MySqlLocked;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Spring 原生拦截器：先开锁事务再进入业务 REQUIRED 事务，无 AspectJ 依赖。 */
@Component
public class MySqlLockAspect implements MethodInterceptor {
    private final MySqlDistributedLock locks;

    public MySqlLockAspect(MySqlDistributedLock locks) {
        this.locks = locks;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        var method = AopUtils.getMostSpecificMethod(invocation.getMethod(), invocation.getThis().getClass());
        MySqlLocked locked = Objects.requireNonNull(AnnotationUtils.findAnnotation(method, MySqlLocked.class));
        try {
            return locks.execute(locked.value(), () -> {
                try {
                    return invocation.proceed();
                } catch (RuntimeException | Error error) {
                    throw error;
                } catch (Throwable error) {
                    throw new InvocationFailure(error);
                }
            });
        } catch (InvocationFailure failure) {
            throw failure.getCause();
        }
    }

    private static class InvocationFailure extends RuntimeException {
        private InvocationFailure(Throwable cause) {
            super(cause);
        }
    }
}
