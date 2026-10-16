package com.neocat.common.config

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import spock.lang.Specification

/** 仅加载 Boot 配置数据，不运行 Apollo 初始化器，不连接远端服务。 */
class ApolloBootstrapSpec extends Specification {
    def 'Boot 显式导入 Apollo 启动配置，meta 不写入文件'() {
        given:
        def context = new GenericApplicationContext()

        when:
        new ConfigDataApplicationContextInitializer().initialize(context)
        def environment = context.environment
        def bootstrap = environment.propertySources.find { it.name.contains('bootstrap.properties') }

        then:
        environment.getProperty('spring.config.import') == 'classpath:bootstrap.properties'
        bootstrap != null
        bootstrap.getProperty('app.id') == 'neocat'
        bootstrap.getProperty('apollo.bootstrap.enabled') == 'true'
        bootstrap.getProperty('apollo.bootstrap.namespaces') == 'application'
        !bootstrap.containsProperty('apollo.meta')

        cleanup:
        context.close()
    }
}
