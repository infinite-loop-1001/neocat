package com.neocat.web

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.core.type.filter.AssignableTypeFilter
import spock.lang.Specification
import java.time.Clock

/** 扫描真实业务组件与代理；数据源、MyBatis Mapper、ClickHouse 查询仅 Spock Mock/Stub。 */
class ComponentScanWiringSpec extends Specification {
    def "全业务组件扫描能组装，禁用真实中间件与调度，不启动消费线程"() {
        given:
        def mysql = Mock(javax.sql.DataSource)
        def clickhouse = Mock(javax.sql.DataSource)
        def context = new AnnotationConfigApplicationContext()
        context.registerBean('dataSource', javax.sql.DataSource, { mysql } as java.util.function.Supplier,
                { it.primary = true } as org.springframework.beans.factory.config.BeanDefinitionCustomizer)
        context.registerBean('clickHouseDataSource', javax.sql.DataSource, { clickhouse } as java.util.function.Supplier)
        context.registerBean('mysqlTransactionManager', org.springframework.transaction.PlatformTransactionManager,
                { new org.springframework.jdbc.support.JdbcTransactionManager(mysql) } as java.util.function.Supplier)
        context.registerBean(Clock, { Clock.systemUTC() } as java.util.function.Supplier)
        context.registerBean(com.fasterxml.jackson.databind.ObjectMapper,
                { new com.fasterxml.jackson.databind.ObjectMapper() } as java.util.function.Supplier)
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate,
                { new org.springframework.jdbc.core.JdbcTemplate(mysql) } as java.util.function.Supplier)
        context.registerBean(com.neocat.common.queue.QueueFactory,
                { new com.neocat.common.queue.impl.BoundedDropQueueFactory() } as java.util.function.Supplier)
        context.registerBean(com.neocat.trace.infra.clickhouse.RawTreeQuery,
                { Stub(com.neocat.trace.infra.clickhouse.RawTreeQuery) } as java.util.function.Supplier)
        [com.neocat.identity.infra.jdbc.AccountMapper,
         com.neocat.identity.infra.jdbc.SessionMapper,
         com.neocat.identity.infra.jdbc.AccessHistoryMapper,
         com.neocat.organization.infra.jdbc.OrgNodeMapper,
         com.neocat.organization.infra.jdbc.MembershipMapper,
         com.neocat.organization.infra.jdbc.EffectiveLeafMapper,
         com.neocat.dashboard.infra.jdbc.DashboardMapper,
         com.neocat.alert.infra.jdbc.AlertMapper,
         com.neocat.catalog.infra.CatalogMapper].each { type ->
            def mapper = Stub(type)
            context.registerBean(type, { mapper } as java.util.function.Supplier)
        }
        def platformMapper = Stub(com.neocat.platform.infra.PlatformMapper) {
            selectProfile() >> null
            selectChannels() >> []
        }
        context.registerBean(com.neocat.platform.infra.PlatformMapper, { platformMapper } as java.util.function.Supplier)
        context.environment.propertySources.addFirst(new org.springframework.core.env.MapPropertySource('offline',
                ['neocat.platform.init.timezone':'UTC']))
        def scanner = new ClassPathBeanDefinitionScanner(context)
        scanner.addExcludeFilter({ metadata, factory ->
            metadata.resource.URL.toString().contains('/test-classes/')
        } as org.springframework.core.type.filter.TypeFilter)
        [com.neocat.NeoCatApplication,
         com.neocat.common.config.impl.RuntimeConfiguration,
         com.neocat.common.config.impl.MySqlConnection,
         com.neocat.trace.infra.clickhouse.ClickHouseConnection].each {
            scanner.addExcludeFilter(new AssignableTypeFilter(it))
        }
        scanner.scan('com.neocat')

        when:
        context.refresh()

        then:
        context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController).size() == 11
        context.getBean(com.neocat.analysis.domain.analyzer.RealtimeConsumer).analyzers().size() == 6
        !context.getBean(com.neocat.analysis.infra.job.RealtimeConsumerLoop).running
        org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.neocat.organization.domain.tree.OrgTreeService))
        org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.neocat.identity.domain.account.AccountService))
        0 * mysql.getConnection()
        0 * clickhouse.getConnection()

        cleanup:
        context.close()
    }
}
