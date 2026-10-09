package com.neocat.alert.domain.recipient;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 组织被删除：该叶子的组织告警规则失效但保留配置。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public final class OrgDeleted implements RecipientEvent {

    private final long orgId;

    public OrgDeleted(long orgId) {
        this.orgId = orgId;
    }

}
