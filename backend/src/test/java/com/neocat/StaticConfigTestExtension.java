package com.neocat;

import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;

import java.util.Objects;

/** 每个测试实例初始前保存静态值，清理后恢复；不让配置变更串到下个用例。 */
public class StaticConfigTestExtension implements IGlobalExtension {
    private final ThreadLocal<java.util.Map<java.lang.reflect.Field, Object>> previous = new ThreadLocal<>();
    @Override public void visitSpec(SpecInfo spec) {
        spec.addInitializerInterceptor(invocation -> {
            previous.set(StaticConfigFixture.snapshot());
            StaticConfigFixture.defaults();
            try { invocation.proceed(); }
            catch (Throwable failure) {
                StaticConfigFixture.restore(previous.get());
                previous.remove();
                throw failure;
            }
        });
        spec.addCleanupInterceptor(invocation -> {
            try { invocation.proceed(); }
            finally {
                if (Objects.nonNull(previous.get())) StaticConfigFixture.restore(previous.get());
                previous.remove();
            }
        });
    }
}
