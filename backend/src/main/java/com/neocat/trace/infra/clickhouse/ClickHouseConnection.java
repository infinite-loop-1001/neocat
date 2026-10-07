package com.neocat.trace.infra.clickhouse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 原始树、报表与上报共用的 ClickHouse 连接，独立于 MySQL 主 DataSource。 */
@Configuration
public class ClickHouseConnection {
    @Bean("clickHouseDataSource")
    public DataSource clickHouseDataSource(@Value("${neocat.clickhouse.url}") String url,
                                           @Value("${neocat.clickhouse.username}") String username,
                                           @Value("${neocat.clickhouse.password}") String password) {
        return DataSourceBuilder.create().driverClassName("com.clickhouse.jdbc.ClickHouseDriver")
                .url(url).username(username).password(password).build();
    }
    @Bean
    public RawTreeQuery rawTreeQuery(@org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource")
                                     DataSource dataSource) {
        return new JdbcRawTreeQuery(dataSource);
    }
}
