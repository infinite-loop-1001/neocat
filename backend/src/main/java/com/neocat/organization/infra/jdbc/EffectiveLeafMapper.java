package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EffectiveLeafMapper {
    int deleteByAccount(@Param("userAccount") String userAccount);
    int insert(@Param("userAccount") String userAccount, @Param("orgId") long orgId);
    List<Long> leavesOf(@Param("userAccount") String userAccount);
    List<String> membersOf(@Param("orgId") long orgId);
    int removeOrg(@Param("orgId") long orgId);
}
