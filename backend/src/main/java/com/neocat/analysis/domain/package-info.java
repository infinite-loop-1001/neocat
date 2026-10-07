/**
 * 分析域（跨模块共享契约，链路 16、19–22）。
 *
 * <p>{@code query} 需要聚合行、序列键、分位分布与统计计算，
 * {@code web} 的装配层需要分析器与消费循环，因此本包对外暴露。
 *
 * <p>关键不变式在本包内实现且不可绕过：
 * 「先合并分子与分布，再算 avg / 分位」（{@code DurationDistribution}、
 * 「缺数不等于零」（{@code MinuteBucket} 返回 null 而非 0）。
 */
@org.springframework.modulith.NamedInterface("analysis")
package com.neocat.analysis.domain;
