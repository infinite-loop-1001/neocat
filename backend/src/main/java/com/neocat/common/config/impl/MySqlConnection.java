package com.neocat.common.config.impl;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** MySQL 主数据源，供 MyBatis 使用；ClickHouse 在 trace 模块单独装配。 */
@Configuration
public class MySqlConnection {
    /** 锁拦截与业务 @Transactional 共用唯一 MySQL 事务管理器。 */
    @Bean("mysqlTransactionManager")
    @Primary
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("dataSource") DataSource source) {
        return new JdbcTransactionManager(source);
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties mysqlProperties() {
        return new DataSourceProperties();
    }
    @Bean("dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource mysqlDataSource(DataSourceProperties mysqlProperties) {
        return mysqlProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
    }
}
