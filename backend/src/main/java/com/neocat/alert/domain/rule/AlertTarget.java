package com.neocat.alert.domain.rule;

import com.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.Objects;

/**
 * 告警目标（PRD 06 §1、§8）。
 *
 * <p>两类目标：
 * <ul>
 *   <li>原始指标目标：`服务 + 报表类型 + 指标对象 + 统计项`；</li>
 *   <li>卡片结果目标：引用组织大盘中某张卡片的当前计算结果。</li>
 * </ul>
 *
 * @param kind         目标类型
 * @param cardId       卡片结果目标对应的卡片；原始指标为 0
 * @param service      服务
 * @param reportKind   报表类型
 * @param type         分类
 * @param name         Name
 * @param metricLabels Metric 标签串
 * @param formulaStats 卡片公式引用的统计项；原始指标目标为空
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class AlertTarget {
    private final AlertTargetKind kind;

    private final long cardId;

    private final String service;

    private final String reportKind;

    private final String type;

    private final String name;

    private final String metricLabels;

    private final List<Stat> formulaStats;

    public AlertTarget(
            AlertTargetKind kind, long cardId, String service, String reportKind,
            String type, String name, String metricLabels, List<Stat> formulaStats) {
        this.kind = kind;
        this.cardId = cardId;
        this.service = service;
        this.reportKind = reportKind;
        this.type = type;
        this.name = name;
        this.metricLabels = metricLabels;
        this.formulaStats = formulaStats;
    }

    public static AlertTarget rawMetric(String service, String reportKind, String type, String name) {
        return new AlertTarget(AlertTargetKind.RAW_METRIC, 0, service,
                reportKind, type, name, null, Lists.newArrayList());
    }
    public static AlertTarget cardResult(long cardId, String service, String reportKind,
                                         String type, String name, List<Stat> formulaStats) {
        return new AlertTarget(AlertTargetKind.CARD_RESULT, cardId, service, reportKind, type, name, null,
                List.copyOf(formulaStats));
    }
    public boolean isCardResult() {
        return Objects.equals(kind, AlertTargetKind.CARD_RESULT);
    }
    /** 目标身份：同服务同指标对象视为同一目标。 */
    public String identity() {
        return service + "|" + reportKind + "|" + (Objects.isNull(type) ? "" : type)
                + "|" + (Objects.isNull(name) ? "" : name)
                + "|" + (Objects.isNull(metricLabels) ? "" : metricLabels);
    }
}




