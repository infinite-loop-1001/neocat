package com.neocat.organization.infra.jdbc;

import lombok.Data;

/**
 * 数据库行（列名与 nc_org_node 一致）。
 */
@Data
public class OrgNodeRow {
    private Long id;

    private String name;

    private Long parentId;
}
