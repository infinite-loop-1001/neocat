package com.neocat.identity.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface SessionMapper {
    SessionRepositoryAdapter.SessionRow selectById(@Param("id") String id);

    int insert(@Param("id") String id, @Param("accountId") long accountId,
               @Param("at") Timestamp at, @Param("expiresAt") Timestamp expiresAt);

    int touchIfValid(@Param("id") String id, @Param("at") Timestamp at,
                     @Param("expiresAt") Timestamp expiresAt);

    int deleteById(@Param("id") String id);

    int deleteByAccountId(@Param("accountId") long accountId);
}
