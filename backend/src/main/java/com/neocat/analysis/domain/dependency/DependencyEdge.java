package com.neocat.analysis.domain.dependency;

import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 依赖边（PRD 04 §6–7）。
 *
 * <p>依赖来自跨服务调用关系，是**长期服务关系汇总**，不是一次 Trace。
 *
 * @param upstreamService   上游（调用方）服务名
 * @param downstreamService 下游（被调用方）服务名
 * @param callType          调用类型（RPC / HTTP / MQ …）
 */
@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
public class DependencyEdge {
    private final String upstreamService;

    private final String downstreamService;

    private final String callType;

    public DependencyEdge(String upstreamService, String downstreamService, String callType) {
        this.upstreamService = upstreamService;
        this.downstreamService = downstreamService;
        this.callType = callType;
    }

    public SeriesKey asSeriesKey() {
        return SeriesKey.dependency(upstreamService, downstreamService);
    }
    /** 上游侧的「下游列表」查询键。 */
    public SeriesKey downstreamSeriesKey() {
        return SeriesKey.of(upstreamService, SeriesKind.DEPENDENCY, "DOWNSTREAM", downstreamService);
    }
    /** 下游侧的「上游列表」查询键。 */
    public SeriesKey upstreamSeriesKey() {
        return SeriesKey.of(downstreamService, SeriesKind.DEPENDENCY, "UPSTREAM", upstreamService);
    }
}
