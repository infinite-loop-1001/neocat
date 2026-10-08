package com.neocat.query.infra.port;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.trace.domain.sample.Sample;
import com.neocat.trace.domain.sample.SampleService;
import com.neocat.trace.config.TraceConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.neocat.trace.domain.sample.SampleQuery;

/**
 * 取样读取口（PRD 03 §11）。
 *
 * <p>由 trace 模块实现；查询层只依赖该抽象，因此「原始树不可用」不会影响汇总报表。
 */
@FunctionalInterface
public interface SamplePort {

    /**
     * 按事件时间倒序返回最近 N 条取样。
     *
     * @param service 服务
     * @param type    分类（URL / SQL / …）
     * @param name    名称
     * @param from    起点（含）
     * @param to      终点（不含）
     * @param limit   条数上限（PRD 一期默认 30）
     */
    List<Sample> samples(String service, String type, String name, Instant from, Instant to, int limit);

    /**
     * 基于 trace 模块的默认实现。
     *
     * <p>同时承担两件事：组装查询条件，以及按留存期判定
     * {@code traceAvailable}（超过留存期的条目仍返回，但不可下钻）。
     */
    static SamplePort of(SampleService sampleService) {
        return (service, type, name, from, to, limit) ->
                sampleService.samples(
                        new SampleQuery(service, type, name, null,
                                from.toEpochMilli(), to.toEpochMilli(), null, limit),
                        TimeProvider.now(),
                         Duration.ofDays(TraceConfig.RETENTION_DAYS));
    }
}
