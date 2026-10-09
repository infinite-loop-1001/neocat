package com.neocat.alert.domain.recipient;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 组织成员关系变化。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public final class OrgMembershipChanged implements RecipientEvent {

    private final long accountId;

    private final long orgId;

    // rules: 类字段需要加注释 (业务含义而不是单纯这个字段的翻译)
    private final boolean granted;

    public OrgMembershipChanged(long accountId, long orgId, boolean granted) {
        this.accountId = accountId;
        this.orgId = orgId;
        this.granted = granted;
    }

}
