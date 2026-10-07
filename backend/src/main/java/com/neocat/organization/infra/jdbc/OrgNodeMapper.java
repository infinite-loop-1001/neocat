package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 组织树 Mapper（表 {@code nc_org_node}）。
 */
@Mapper
public interface OrgNodeMapper {

    OrgNodeRepositoryAdapter.OrgNodeRow selectById(@Param("id") long id);

    List<OrgNodeRepositoryAdapter.OrgNodeRow> selectAll();

    List<OrgNodeRepositoryAdapter.OrgNodeRow> selectChildren(@Param("parentId") long parentId);

    int insert(OrgNodeRepositoryAdapter.OrgNodeRow row);

    int update(OrgNodeRepositoryAdapter.OrgNodeRow row);

    void delete(@Param("id") long id);
}
