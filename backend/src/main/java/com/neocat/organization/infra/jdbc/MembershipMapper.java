package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MembershipMapper {
    int add(@Param("orgId") long orgId, @Param("accountId") long accountId);
    int remove(@Param("orgId") long orgId, @Param("accountId") long accountId);
    List<Long> membersOf(@Param("orgId") long orgId);
    List<Long> orgsOf(@Param("accountId") long accountId);
    int removeAllOf(@Param("accountId") long accountId);
}
