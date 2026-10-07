package com.neocat.query.infra.port;

import com.neocat.trace.domain.sample.Sample;

import java.time.Instant;
import java.util.List;

/**
 * 原始树不可用时的取样实现（技术方案 01-architecture.md §6、PRD 02 §10）。
 *
 * <p>用途明确而非临时占位：当部署未启用原始树存储（例如未接通 ClickHouse）时，
 * 汇总报表必须继续可用，只是取样为空、下钻不可用。
 * 这与 PRD 02 §10「Trace 组装失败不影响已经完成的报表统计」是同一设计意图。
 *
 * <p>一旦接入原始树存储，装配层改用 {@link SamplePort#of} 的默认实现即可，
 * 控制器代码无需改动。
 */
public class EmptySamplePort implements SamplePort {

    @Override
    public List<Sample> samples(String service, String type, String name,
                                Instant from, Instant to, int limit) {
        return List.of();
    }
}
