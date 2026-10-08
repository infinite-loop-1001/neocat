package com.neocat.organization.domain.tree;

import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 组织节点（PRD 01 §5）：组织是树，允许多个根节点；只有叶子能拥有大盘。
 */
@NamedInterface("isOrganization")
@Getter
@EqualsAndHashCode
@ToString
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
        return Objects.isNull(parentId);
    }
}
