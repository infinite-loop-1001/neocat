package com.neocat.common.locking

import com.neocat.common.locking.impl.MySqlDistributedLock
import com.neocat.common.locking.impl.MySqlLockAspect
import com.neocat.common.locking.impl.MySqlLockConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Specification

/** 所有数据库边界仅 Mock；不代表真实双连接互斥已经验证。 */
class MySqlDistributedLockSpec extends Specification {
    def jdbc = Mock(JdbcTemplate)

    def manager = Mock(PlatformTransactionManager)

    def status = Mock(TransactionStatus)

    def locks = new MySqlDistributedLock(jdbc, new TransactionTemplate(manager))

    def "事务开启、建立主键行、FOR UPDATE、临界区、提交依次执行"() {
        given:
        def operation = Mock(java.util.function.Supplier)

        when:
        def result = locks.execute('metadata', operation)

        then:
        1 * manager.getTransaction({ it.timeout == 15 && it.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED }) >> status

        then:
        1 * jdbc.update('INSERT INTO nc_distributed_lock (lock_key) VALUES (?) ON DUPLICATE KEY UPDATE lock_key = lock_key', 'metadata') >> 1

        then:
        1 * jdbc.queryForObject('SELECT lock_key FROM nc_distributed_lock WHERE lock_key = ? FOR UPDATE', String, 'metadata') >> 'metadata'

        then:
        1 * operation.get() >> 'done'

        then:
        1 * manager.commit(status)
        result == 'done'
    }

    def "锁等待失败必须回滚且不运行临界区"() {
        given:
        def operation = Mock(java.util.function.Supplier)
        manager.getTransaction(_) >> status
        jdbc.queryForObject(_, String, 'metadata') >> { throw new org.springframework.dao.CannotAcquireLockException('timeout') }

        when:
        locks.execute('metadata', operation)

        then:
        thrown(org.springframework.dao.CannotAcquireLockException)
        1 * manager.rollback(status)
        0 * manager.commit(_)
        0 * operation.get()
    }

    def "业务异常传播并回滚"() {
        given:
        manager.getTransaction(_) >> status
        jdbc.queryForObject(_, String, 'metadata') >> 'metadata'

        when:
        locks.execute('metadata', { throw new IllegalStateException('business failed') })

        then:
        def error = thrown(IllegalStateException)
        error.message == 'business failed'
        1 * manager.rollback(status)
        0 * manager.commit(_)
    }

    def "非法锁键在开事务之前拒绝"() {
        when:
        locks.execute(key, { 'unexpected' })

        then:
        thrown(IllegalArgumentException)
        0 * manager.getTransaction(_)
        0 * jdbc.update(*_)

        where:
        key << [null, '', ' ', 'x' * 129]
    }

    def "锁行建立失败或 SELECT 未命中时不能运行临界区"() {
        given:
        manager.getTransaction(_) >> status
        def operation = Mock(java.util.function.Supplier)
        jdbc.update(_, 'metadata') >> {
            if (insertFails) throw new org.springframework.dao.DataIntegrityViolationException('insert failed')
            1
        }
        jdbc.queryForObject(_, String, 'metadata') >> null

        when:
        locks.execute('metadata', operation)

        then:
        thrown(RuntimeException)
        1 * manager.rollback(status)
        0 * manager.commit(_)
        0 * operation.get()

        where:
        insertFails << [true, false]
    }

    def "真实 Spring 事务代理与嵌套锁复用同一 Mock JDBC 连接，只在最外层释放"() {
        given:
        def source = Mock(javax.sql.DataSource)
        def connection = Mock(java.sql.Connection)
        connection.autoCommit >> true
        def sqlCalls = []
        connection.prepareStatement(_) >> { String sql ->
            sqlCalls.add(sql)
            def rows = Stub(java.sql.ResultSet) {
                next() >>> [true, false]
                getString(1) >> 'metadata'
                getMetaData() >> Stub(java.sql.ResultSetMetaData) {
                    getColumnCount() >> 1
                }
            }
            Stub(java.sql.PreparedStatement) {
                executeUpdate() >> 1
                executeQuery() >> rows
            }
        }
        def actualManager = new com.neocat.common.config.impl.MySqlConnection().mysqlTransactionManager(source)
        def context = new AnnotationConfigApplicationContext()
        context.registerBean('dataSource', javax.sql.DataSource, { source } as java.util.function.Supplier)
        context.registerBean('mysqlTransactionManager', PlatformTransactionManager, { actualManager } as java.util.function.Supplier)
        context.register(MySqlLockConfiguration, MySqlLockAspect, MySqlDistributedLock, NestedService, OuterService)
        context.refresh()

        when:
        Throwable error = null
        try {
            context.getBean(OuterService).run(fail)
        } catch (Throwable caught) {
            error = caught
        }

        then:
        fail ? error instanceof IllegalStateException && error.message == 'business failed' : error == null
        1 * source.getConnection() >> connection
        (fail ? 0 : 1) * connection.commit()
        (fail ? 1 : 0) * connection.rollback()
        1 * connection.close()
        sqlCalls.count { it.startsWith('INSERT INTO nc_distributed_lock') } == 2
        sqlCalls.count { it == 'SELECT lock_key FROM nc_distributed_lock WHERE lock_key = ? FOR UPDATE' } == 2
        sqlCalls.last() == 'UPDATE nc_test_boundary SET value = ? WHERE id = ?'
        context.getBean(MySqlDistributedLock) != null

        cleanup:
        context.close()

        where:
        fail << [false, true]
    }

    def "Spring 原生代理实际拦截注解方法而不是仅反射看到注解"() {
        given:
        manager.getTransaction(_) >> status
        jdbc.queryForObject(_, String, 'metadata') >> 'metadata'
        def context = new AnnotationConfigApplicationContext()
        context.registerBean(MySqlDistributedLock, { locks } as java.util.function.Supplier)
        context.register(MySqlLockConfiguration, MySqlLockAspect, CriticalService)
        context.refresh()

        when:
        def result = context.getBean(CriticalService).run()

        then:
        result == 'locked'
        1 * manager.commit(status)
        org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(CriticalService))

        cleanup:
        context.close()
    }

    static class CriticalService {
        @MySqlLocked('metadata')
        String run() { 'locked' }
    }

    static class OuterService {
        final NestedService nested

        OuterService(NestedService nested) { this.nested = nested }

        @MySqlLocked('metadata')
        @org.springframework.transaction.annotation.Transactional
        void run(boolean fail) { nested.run(fail) }
    }

    static class NestedService {
        final JdbcTemplate jdbc

        NestedService(javax.sql.DataSource source) { this.jdbc = new JdbcTemplate(source) }

        @MySqlLocked('metadata')
        @org.springframework.transaction.annotation.Transactional
        void run(boolean fail) {
            jdbc.update('UPDATE nc_test_boundary SET value = ? WHERE id = ?', 'test', 1)
            if (fail) throw new IllegalStateException('business failed')
        }
    }
}
