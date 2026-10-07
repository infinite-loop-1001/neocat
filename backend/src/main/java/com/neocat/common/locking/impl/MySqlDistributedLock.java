package com.neocat.common.locking.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Supplier;

/** 主键行锁，事务提交/回滚释放；查询或加锁失败绝不执行临界区。 */
@Component
public class MySqlDistributedLock {
    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public MySqlDistributedLock(@Qualifier("dataSource") DataSource source,
                               @Qualifier("mysqlTransactionManager") org.springframework.transaction.PlatformTransactionManager manager) {
        this(new JdbcTemplate(source), new TransactionTemplate(manager));
    }

    /** 明确的离线测试接缝，不连接数据库。 */
    public MySqlDistributedLock(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.jdbc.setQueryTimeout(10);
        this.transactions.setTimeout(15);
    }

    public <T> T execute(String key, Supplier<T> operation) {
        if (Objects.isNull(key) || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("锁键必须为 1–128 个字符");
        }
        Objects.requireNonNull(operation);
        return transactions.execute(status -> {
            // 非 IGNORE：除主键冲突外的 SQL 错误必须传播。锁键使用二进制排序规则。
            jdbc.update("INSERT INTO nc_distributed_lock (lock_key) VALUES (?) "
                    + "ON DUPLICATE KEY UPDATE lock_key = lock_key", key);
            String locked = jdbc.queryForObject(
                    "SELECT lock_key FROM nc_distributed_lock WHERE lock_key = ? FOR UPDATE", String.class, key);
            if (!key.equals(locked)) {
                throw new IllegalStateException("未获得指定锁行：" + key);
            }
            return operation.get();
        });
    }
}
