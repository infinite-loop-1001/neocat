package com.neocat.identity.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 最近访问服务 Mapper（表 {@code nc_access_history}）。
 *
 * <p>登录落点依赖该表：有可用最近访问服务则进入该服务 Transaction，
 * 否则进入服务列表（PRD 01 §4.1）。
 */
@Mapper
public interface AccessHistoryMapper {

    int upsert(@Param("accountId") long accountId,
               @Param("serviceName") String serviceName,
               @Param("accessedAt") Timestamp accessedAt);

    List<String> selectRecentServices(@Param("accountId") long accountId, @Param("limit") int limit);
}
