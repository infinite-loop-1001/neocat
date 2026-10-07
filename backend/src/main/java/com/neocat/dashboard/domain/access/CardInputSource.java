package com.neocat.dashboard.domain.access;

import com.neocat.dashboard.domain.card.Card;

import com.neocat.query.domain.stat.Stat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 卡片公式输入来源（PRD 05 §5）。
 *
 * <p>由查询层实现；dashboard 只依赖该抽象，因此卡片求值逻辑
 * （桶对齐、缺数入缺、除零不可计算）可以独立于存储测试。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public interface CardInputSource {

    /**
     * 读取某卡片目标在给定时间范围内、逐桶的各统计项值。
     *
     * @param card   卡片（提供目标与公式）
     * @param from   起点（含）
     * @param to     终点（不含）
     * @param bucketSeconds 桶粒度
     * @return 桶起点 → (统计项 → 值)；值缺失表示该桶该输入缺数
     */
    Map<Long, Map<Stat, Double>> buckets(Card card, Instant from, Instant to, long bucketSeconds);

    /**
     * 时间桶边界列表（左闭右开），供求值与前端展示对齐。
     */
    List<long[]> bucketBoundaries(Instant from, Instant to, long bucketSeconds);
}
