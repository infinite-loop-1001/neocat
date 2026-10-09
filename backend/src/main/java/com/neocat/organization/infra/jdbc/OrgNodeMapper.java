package com.neocat.organization.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 组织树 Mapper（表 {@code nc_org_node}）。
 */
@Mapper
public interface OrgNodeMapper {

    OrgNodeRow selectById(@Param("id") long id);

    List<OrgNodeRow> selectAll();

    List<OrgNodeRow> selectChildren(@Param("parentId") long parentId);

    int insert(OrgNodeRow row);

    int update(OrgNodeRow row);

    void delete(@Param("id") long id);
}
