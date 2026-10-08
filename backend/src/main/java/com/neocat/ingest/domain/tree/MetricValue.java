package com.neocat.ingest.domain.tree;

import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * Metric 节点载荷：指标名 + 若干标签键值 + 数值。
 * 标签键由上报方自定，平台不预注册（PRD 04 §1）。
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class MetricValue {
    private final String name;

    private final double value;

    private final Map<String, String> labels;

    public MetricValue(String name, double value, Map<String, String> labels) {
        this.name = name;
        this.value = value;
        this.labels = labels;
    }

}
