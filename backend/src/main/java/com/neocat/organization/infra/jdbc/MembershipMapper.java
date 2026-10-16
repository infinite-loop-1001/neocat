package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MembershipMapper {
    int add(@Param("orgId") long orgId, @Param("userAccount") String userAccount);
    int remove(@Param("orgId") long orgId, @Param("userAccount") String userAccount);
    List<String> membersOf(@Param("orgId") long orgId);
    List<Long> orgsOf(@Param("userAccount") String userAccount);
    int removeAllOf(@Param("userAccount") String userAccount);
}
