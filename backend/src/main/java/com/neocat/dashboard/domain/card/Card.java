package com.neocat.dashboard.domain.card;

import com.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 卡片（PRD 05 §3、§4、§7）。
 *
 * <p>**一张卡片只能绑定「一个服务 + 一个指标对象」**。
 * 同一大盘可通过多张卡片覆盖不同服务与指标对象，
 * 但一张卡片不能跨服务、跨 Name 组合输入。
 *
 * @param id             卡片 ID
 * @param dashboardId    所属大盘
 * @param service        服务名
 * @param targetKind     指标对象类型（TRANSACTION / EVENT / PROBLEM / METRIC / HEARTBEAT）
 * @param targetType     分类，如 URL / SQL
 * @param targetName     Name
 * @param metricLabels   Metric 标签串（规范化后）；非 Metric 为 null
 * @param instanceScope  维度范围；空表示全部机器聚合
 * @param formula        公式源码
 * @param timeRange      时间范围
 * @param orderNo        卡片顺序
 * @param thresholdLines 阈值线：纯视觉对照，**不驱动告警阈值也不按机器展开**（PRD 05 §7）
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public class Card {
    private final long id;

    private final long dashboardId;

    private final String service;

    private final String targetKind;

    private final String targetType;

    private final String targetName;

    private final String metricLabels;

    private final List<String> instanceScope;

    private final String formula;

    private final String timeRange;

    private final int orderNo;

    private final List<ThresholdLine> thresholdLines;

    public Card(long id, long dashboardId, String service, String targetKind, String targetType, String targetName, String metricLabels, List<String> instanceScope, String formula, String timeRange, int orderNo, List<ThresholdLine> thresholdLines) {
        this.id = id;
        this.dashboardId = dashboardId;
        this.service = service;
        this.targetKind = targetKind;
        this.targetType = targetType;
        this.targetName = targetName;
        this.metricLabels = metricLabels;
        this.instanceScope = instanceScope;
        this.formula = formula;
        this.timeRange = timeRange;
        this.orderNo = orderNo;
        this.thresholdLines = thresholdLines;
    }

    /** 无阈值线的卡片（新建场景）。 */
    public static Card withoutThresholds(long id, long dashboardId, String service, String targetKind,
                                         String targetType, String targetName, String metricLabels,
                                         List<String> instanceScope, String formula, String timeRange,
                                         int orderNo) {
        return new Card(id, dashboardId, service, targetKind, targetType, targetName, metricLabels,
                instanceScope, formula, timeRange, orderNo, Lists.newArrayList());
    }
    /** 卡片目标是否覆盖某个统计项（供组织告警可用目标并集计算）。 */
    public boolean refer1ences(Stat stat) {
        return Objects.nonNull(formula) && formula.toLowerCase(Locale.ROOT)
                .contains(stat.name().toLowerCase(Locale.ROOT).replace("_", ""));
    }
    /** 目标标识：用于判定两张卡片是否指向同一指标对象。 */
    public String targetIdentity() {
        return service + "|" + targetKind + "|" + (Objects.isNull(targetType) ? "" : targetType)
                + "|" + (Objects.isNull(targetName) ? "" : targetName)
                + "|" + (Objects.isNull(metricLabels) ? "" : metricLabels);
    }
}