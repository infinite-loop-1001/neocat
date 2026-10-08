package com.neocat.web

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.core.type.filter.AssignableTypeFilter
import spock.lang.Specification
import java.time.Clock
import com.fasterxml.jackson.databind.ObjectMapper
import com.neocat.NeoCatApplication
import com.neocat.RuntimeConfiguration
import com.neocat.alert.infra.jdbc.AlertMapper
import com.neocat.analysis.domain.analyzer.RealtimeConsumer
import com.neocat.analysis.infra.job.RealtimeConsumerLoop
import com.neocat.catalog.infra.CatalogMapper
import com.neocat.common.config.impl.MySqlConnection
import com.neocat.common.queue.QueueFactory
import com.neocat.common.queue.impl.BoundedDropQueueFactory
import com.neocat.dashboard.infra.jdbc.DashboardMapper
import com.neocat.identity.domain.account.AccountService
import com.neocat.identity.infra.jdbc.AccessHistoryMapper
import com.neocat.identity.infra.jdbc.AccountMapper
import com.neocat.identity.infra.jdbc.SessionMapper
import com.neocat.organization.domain.tree.OrgTreeService
import com.neocat.organization.infra.jdbc.EffectiveLeafMapper
import com.neocat.organization.infra.jdbc.MembershipMapper
import com.neocat.organization.infra.jdbc.OrgNodeMapper
import com.neocat.platform.infra.PlatformMapper
import com.neocat.trace.infra.clickhouse.ClickHouseConnection
import com.neocat.trace.infra.clickhouse.RawTreeQuery
import java.util.function.Supplier
import javax.sql.DataSource
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.core.env.MapPropertySource
import org.springframework.core.type.filter.TypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.RestController

/** 扫描真实业务组件与代理；数据源、MyBatis Mapper、ClickHouse 查询仅 Spock Mock/Stub。 */
class ComponentScanWiringSpec extends Specification {
    def "全业务组件扫描能组装，禁用真实中间件与调度，不启动消费线程"() {
        given:
        def mysql = Mock(DataSource)
        def clickhouse = Mock(DataSource)
        def context = new AnnotationConfigApplicationContext()
        context.registerBean('dataSource', DataSource, { mysql } as Supplier,
                { it.primary = true } as BeanDefinitionCustomizer)
        context.registerBean('clickHouseDataSource', DataSource, { clickhouse } as Supplier)
        context.registerBean('mysqlTransactionManager', PlatformTransactionManager,
                { new JdbcTransactionManager(mysql) } as Supplier)
        context.registerBean(ObjectMapper,
                { new ObjectMapper() } as Supplier)
        context.registerBean(JdbcTemplate,
                { new JdbcTemplate(mysql) } as Supplier)
        context.registerBean(QueueFactory,
                { new BoundedDropQueueFactory() } as Supplier)
        context.registerBean(RawTreeQuery,
                { Stub(RawTreeQuery) } as Supplier)
        [AccountMapper,
         SessionMapper,
         AccessHistoryMapper,
         OrgNodeMapper,
         MembershipMapper,
         EffectiveLeafMapper,
         DashboardMapper,
         AlertMapper,
         CatalogMapper].each { type ->
            def mapper = Stub(type)
            context.registerBean(type, { mapper } as Supplier)
        }
        def platformMapper = Stub(PlatformMapper) {
            selectProfile() >> null
            selectChannels() >> []
        }
        context.registerBean(PlatformMapper, { platformMapper } as Supplier)
        context.environment.propertySources.addFirst(new MapPropertySource('offline',
                ['neocat.platform.init.timezone':'UTC']))
        def scanner = new ClassPathBeanDefinitionScanner(context)
        scanner.addExcludeFilter({ metadata, factory ->
            metadata.resource.URL.toString().contains('/test-classes/')
        } as TypeFilter)
        [NeoCatApplication,
         RuntimeConfiguration,
         MySqlConnection,
         ClickHouseConnection].each {
            scanner.addExcludeFilter(new AssignableTypeFilter(it))
        }
        scanner.scan('com.neocat')

        when:
        context.refresh()

        then:
        context.getBeansOfType(Clock).isEmpty()
        context.getBeansWithAnnotation(RestController).size() == 11
        context.getBean(RealtimeConsumer).analyzers().size() == 6
        !context.getBean(RealtimeConsumerLoop).running
        AopUtils.isAopProxy(context.getBean(OrgTreeService))
        AopUtils.isAopProxy(context.getBean(AccountService))
        0 * mysql.getConnection()
        0 * clickhouse.getConnection()

        cleanup:
        context.close()
    }
}
