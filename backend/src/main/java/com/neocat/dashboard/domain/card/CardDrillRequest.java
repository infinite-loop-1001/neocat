package com.neocat.dashboard.domain.card;

import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 卡片维度下钻（PRD 05 §6、§7，技术方案 02 §9.3）。
 *
 * <p>下钻请求 DTO。
 *
 * @param cardId        卡片
 * @param instances     勾选的机器；空表示聚合视图
 * @param topN          Top N 机器
 * @param thresholdLines 阈值线（作用于聚合结果）
 */
@org.springframework.modulith.NamedInterface("dashboard")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class CardDrillRequest {
    private final long cardId;

    private final List<String> instances;

    private final int topN;

    private final List<ThresholdLine> thresholdLines;

    public CardDrillRequest(long cardId, List<String> instances, int topN, List<ThresholdLine> thresholdLines) {
        this.cardId = cardId;
        this.instances = instances;
        this.topN = topN;
        this.thresholdLines = thresholdLines;
    }

    /** 聚合模式：不勾选任何机器。 */
    public boolean aggregateMode() {
        return CollectionUtils.isEmpty(instances);
    }
}
