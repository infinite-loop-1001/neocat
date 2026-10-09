package com.neocat.trace.domain.tree;

import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 组装结果。
 *
 * <p>root：根节点；树完全不可用时为 null。
 * <p>expired：种子树已过期（曾收到但超期）。
 * <p>missing：种子树从未收到。
 */
@NamedInterface("trace")
@Getter
@EqualsAndHashCode
@ToString
public class AssemblyResult {
    private final TraceTreeNode root;

    private final boolean expired;

    private final boolean missing;

    public AssemblyResult(TraceTreeNode root, boolean expired, boolean missing) {
        this.root = root;
        this.expired = expired;
        this.missing = missing;
    }

    public boolean usable() {
        return Objects.nonNull(root);
    }
}
