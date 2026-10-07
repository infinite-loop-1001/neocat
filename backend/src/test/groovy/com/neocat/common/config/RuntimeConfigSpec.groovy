package com.neocat.common.config

import com.neocat.StaticConfigFixture
import com.neocat.common.config.impl.ApolloConfigGuard
import link.cu1universe.dev.apollo.annotation.ApolloStaticValue
import link.cu1universe.dev.apollo.processor.ApolloStaticValueProcessor
import org.springframework.mock.env.MockEnvironment
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.Modifier

class RuntimeConfigSpec extends Specification {
    def "动态配置键按用途声明为 Configuration 中的 public static volatile 常量，无默认占位符"() {
        expect: "字段是规范常量命名，且注解只含一个无默认值的占位符"
        ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.every { type ->
            type.getAnnotation(org.springframework.context.annotation.Configuration) != null &&
                type.declaredFields.every { field ->
                    Modifier.isPublic(field.modifiers) && Modifier.isStatic(field.modifiers) &&
                    Modifier.isVolatile(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                    field.name ==~ /[A-Z][A-Z0-9_]*/ &&
                    field.getAnnotation(ApolloStaticValue).value() ==~ /\$\{[^:}]+}/
                }
        }
    }

    def "真实 common-apollo 处理器从 Environment 初始化静态字段"() {
        given:
        def environment = new MockEnvironment()
        ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.each { type -> type.declaredFields.each { field ->
            environment.setProperty(keyOf(field), field.get(null).toString())
        } }
        environment.setProperty('neocat.ingest.batch-size', '17')
        def processor = new ApolloStaticValueProcessor()
        processor.setEnvironment(environment)

        when:
        ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.each {
            processor.postProcessBeforeInitialization(it.getDeclaredConstructor().newInstance(), it.simpleName)
        }

        then:
        IngestConfig.BATCH_SIZE == 17
        IngestConfig.QUEUE_CAPACITY == 65536
        TraceConfig.SAMPLE_RATE == 1.0d
    }

    def "真实处理器变更静态字段，不复制到配置对象中"() {
        given:
        def processor = new ApolloStaticValueProcessor()
        processor.setEnvironment(new MockEnvironment().withProperty('neocat.metric.top-n', '2'))
        processor.postProcessBeforeInitialization(new MetricConfig(), 'metricConfig')
        def method = ApolloStaticValueProcessor.getDeclaredMethod('onConfigChange', com.ctrip.framework.apollo.model.ConfigChangeEvent)
        method.accessible = true
        def change = new com.ctrip.framework.apollo.model.ConfigChange('neocat', 'application', 'neocat.metric.top-n', '2', '5',
                com.ctrip.framework.apollo.enums.PropertyChangeType.MODIFIED)

        when:
        method.invoke(processor, new com.ctrip.framework.apollo.model.ConfigChangeEvent('neocat', 'application', ['neocat.metric.top-n': change]))

        then:
        MetricConfig.TOP_N == 5
    }

    def "必需键齐全时校验通过"() {
        given:
        def environment = completeEnvironment()

        expect:
        ApolloConfigGuard.validate(environment)
    }

    def "缺动态键时拒绝启动并指出键名"() {
        given:
        def environment = completeEnvironment()
        environment.setProperty('neocat.trace.retention-days', '')

        when:
        ApolloConfigGuard.validate(environment)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('neocat.trace.retention-days')
    }

    @Unroll
    def "非法值 '#key=#value' 拒绝启动"() {
        given:
        def environment = completeEnvironment()
        environment.setProperty(key, value)

        when:
        ApolloConfigGuard.validate(environment)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains(key)

        where:
        key                                   | value
        'neocat.ingest.queue.capacity'        | '0'
        'neocat.ingest.queue.capacity'        | 'not-a-number'
        'neocat.trace.sample-rate'            | '2.0'
        'neocat.alert.dedup-per-minute'       | 'maybe'
        'server.port'                         | '70000'
        'neocat.platform.init.timezone'       | 'Not/AZone'
    }

    def "空口令视为合法配置，不被当作缺键"() {
        given:
        def environment = completeEnvironment()
        environment.setProperty('spring.datasource.password', '')
        environment.setProperty('neocat.clickhouse.password', '')

        expect:
        ApolloConfigGuard.validate(environment)
    }

    private static String keyOf(java.lang.reflect.Field field) {
        def placeholder = field.getAnnotation(ApolloStaticValue).value()
        placeholder.substring(2, placeholder.length() - 1)
    }

    /** 构造一份完整合法的配置，用于只破坏单个键的负例。 */
    private static MockEnvironment completeEnvironment() {
        def environment = new MockEnvironment()
        ApolloConfigGuard.FRAMEWORK_KEYS.each { environment.setProperty(it, 'x') }
        ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.each { type -> type.declaredFields.each { field ->
            def type_ = field.getType()
            def value = type_ == double.class ? '1.0' : type_ == boolean.class ? 'true' : '10'
            environment.setProperty(keyOf(field), value)
        } }
        environment.setProperty('server.port', '8080')
        environment.setProperty('neocat.platform.init.timezone', 'Asia/Shanghai')
        environment
    }
}
