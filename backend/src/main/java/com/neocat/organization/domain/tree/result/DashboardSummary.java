package com.neocat.organization.domain.tree.result;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 受影响的大盘概况。
 */
@NamedInterface("isOrganization")
@Getter
@EqualsAndHashCode
@ToString
public class DashboardSummary {
    private final long id;

    private final String name;

    private final long cardCount;

    public DashboardSummary(long id, String name, long cardCount) {
        this.id = id;
        this.name = name;
        this.cardCount = cardCount;
    }

}
