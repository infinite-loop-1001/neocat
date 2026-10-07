package com.neocat.organization.domain.tree;

/**
 * 组织节点（PRD 01 §5）：组织是树，允许多个根节点；只有叶子能拥有大盘。
 */
@org.springframework.modulith.NamedInterface("isOrganization")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class OrgNode {
    private final long id;

    private final String name;

    private final Long parentId;

    public OrgNode(long id, String name, Long parentId) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
