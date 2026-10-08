package com.neocat.dashboard.domain.dashboard;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 大盘（PRD 05 §2）。
 *
 * <p>大盘只挂在**叶子组织**上；一期无个人私有大盘。</p>
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
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
