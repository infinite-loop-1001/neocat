package com.neocat.dashboard.domain.dashboard;

/**
 * 大盘（PRD 05 §2）。
 *
 * <p>大盘只挂在**叶子组织**上；一期无个人私有大盘。</p>
 */
@org.springframework.modulith.NamedInterface("dashboard")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class Dashboard {
    private final long id;

    private final long orgId;

    private final String name;

    private final int orderNo;

    public Dashboard(long id, long orgId, String name, int orderNo) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.orderNo = orderNo;
    }

}

