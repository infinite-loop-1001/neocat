package com.neocat.common.config

import java.lang.reflect.Field

import com.neocat.analysis.config.MetricConfig

import com.ctrip.framework.apollo.Config
import com.ctrip.framework.apollo.ConfigChangeListener
import com.ctrip.framework.apollo.ConfigService
import com.ctrip.framework.apollo.internals.ConfigManager
import com.ctrip.framework.apollo.model.ConfigChange
import com.ctrip.framework.apollo.model.ConfigChangeEvent
import com.ctrip.framework.apollo.enums.PropertyChangeType
import com.ctrip.framework.apollo.spring.annotation.ApolloAnnotationProcessor
import com.neocat.ApolloConfigGuard
import link.cu1universe.dev.apollo.autoconfigure.ApolloAutoConfiguration
import link.cu1universe.dev.apollo.processor.ApolloStaticValueProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import spock.lang.Specification
import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig
import com.neocat.RuntimeConfiguration
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.infra.store.InMemoryMetricHourRank
import java.time.Instant
import link.cu1universe.dev.apollo.annotation.ApolloStaticValue

/**
 * 使用真实 common-apollo 自动配置与原生注解处理器，只替换 Apollo 的 ConfigManager
 * 外部边界，不访问网络。
 *
 * <p>覆盖三件事：自动配置被激活、静态字段在业务 Bean 之前初始化、以及
 * 推送到达后同一业务对象读到新值（不存在配置副本）。
 */
class ApolloStaticWiringSpec extends Specification {
    @Configuration(proxyBeanMethods = false)
    @EnableApolloConfig
    static class NativeProcessor {
    }

    @Configuration(proxyBeanMethods = false)
    @DependsOn('metricConfig')
    static class Consumer {
        @Bean Object initializedConsumer() {
            assert MetricConfig.TOP_N == 2
            return new Object()
        }
    }

    def "common-apollo 自动配置注册监听器，Configuration 字段先初始化，推送后同一业务对象读新值"() {
        given:
        def listeners = installConfig(['neocat.metric.top-n': '2'])
        def runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApolloAutoConfiguration))
            .withUserConfiguration(NativeProcessor, MetricConfig, Consumer)
            .withPropertyValues('apollo.bootstrap.namespaces=application', 'neocat.metric.top-n=2')

        expect:
        runner.run { context ->
            assert context.startupFailure == null
            assert context.getBean(ApolloStaticValueProcessor) != null
            assert context.getBean(ApolloAnnotationProcessor) != null
            assert context.getBean('initializedConsumer') != null
            assert !listeners.isEmpty()
            def rank = new InMemoryMetricHourRank()
            def hour = Instant.parse('2026-10-03T00:00:00Z')
            assert rank.record('s', 'm', 'a', hour) == 'a'
            assert rank.record('s', 'm', 'b', hour) == 'b'
            assert rank.record('s', 'm', 'c', hour) == SeriesKey.OTHER_LABELS
            def event = new ConfigChangeEvent('neocat', 'application', [
                'neocat.metric.top-n': new ConfigChange('neocat', 'application', 'neocat.metric.top-n', '2', '3', PropertyChangeType.MODIFIED)
            ])
            listeners.each { it.onChange(event) }
            assert MetricConfig.TOP_N == 3
            assert rank.record('s', 'm', 'd', hour) == 'd'
        }

        cleanup:
        restoreConfig()
    }

    def "Apollo 属性源缺键时启动校验拒绝启动，不静默用 0/null 运行"() {
        given:
        installConfig([:])
        def runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApolloAutoConfiguration))
            .withUserConfiguration(NativeProcessor, MetricConfig,
                    RuntimeConfiguration)

        expect:
        runner.run { context ->
            assert context.startupFailure != null
            assert context.startupFailure.message.contains('Apollo 配置校验失败')
        }

        cleanup:
        restoreConfig()
    }

    def "Apollo 属性源齐备时启动校验放行"() {
        given:
        def values = [:]
        ApolloConfigGuard.FRAMEWORK_KEYS.each { values[it] = 'x' }
        ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.each { type -> type.declaredFields.each { field ->
            def fieldType = field.getType()
            values[keyOf(field)] = fieldType == double.class ? '1.0'
                    : fieldType == boolean.class ? 'true' : '10'
        } }
        values['server.port'] = '8080'
        values['neocat.platform.init.timezone'] = 'Asia/Shanghai'
        installConfig(values)
        def runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApolloAutoConfiguration))
            .withUserConfiguration(NativeProcessor, MetricConfig,
                    RuntimeConfiguration)

        expect:
        runner.run { context ->
            assert context.startupFailure == null
        }

        cleanup:
        restoreConfig()
    }

    private static String keyOf(Field field) {
        def placeholder = field.getAnnotation(ApolloStaticValue).value()
        placeholder.substring(2, placeholder.length() - 1)
    }

    // ── 替换 Apollo 的 ConfigManager，避免真实网络 ─────────────────

    private Map installed

    private List<ConfigChangeListener> installConfig(Map<String, String> values) {
        def listeners = []
        def config = Stub(Config) {
            getPropertyNames() >> values.keySet()
            // 第二参是 null：ConfigPropertySource 调 getProperty(key, null)，
            // 因此这里必须用 _，_ as String 不匹配 null。
            getProperty(_ as String, _) >> { String key, ignored -> values[key] }
            addChangeListener(_ as ConfigChangeListener) >> { ConfigChangeListener listener -> listeners.add(listener) }
        }
        def manager = Stub(ConfigManager) {
            getConfig('application') >> config
            getConfig(_, 'application') >> config
        }
        def instanceField = ConfigService.getDeclaredField('s_instance')
        instanceField.accessible = true
        def service = instanceField.get(null)
        def managerField = ConfigService.getDeclaredField('m_configManager')
        managerField.accessible = true
        installed = [service: service, managerField: managerField, previous: managerField.get(service)]
        managerField.set(service, manager)
        listeners
    }

    private void restoreConfig() {
        if (installed == null) {
            return
        }
        installed.managerField.set(installed.service, installed.previous)
        installed = null
    }
}
