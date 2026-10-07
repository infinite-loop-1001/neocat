package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EffectiveLeafMapper {
    int deleteByAccount(@Param("accountId") long accountId);
    int insert(@Param("accountId") long accountId, @Param("orgId") long orgId);
    List<Long> leavesOf(@Param("accountId") long accountId);
    List<Long> membersOf(@Param("orgId") long orgId);
    int removeOrg(@Param("orgId") long orgId);
}
