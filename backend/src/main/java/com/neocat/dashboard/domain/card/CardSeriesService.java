package com.neocat.dashboard.domain.card;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.neocat.dashboard.domain.access.CardInputSource;
import com.neocat.dashboard.domain.formula.Formula;
import com.neocat.dashboard.domain.formula.FormulaParser;
import com.neocat.query.domain.stat.Stat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.ListUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 卡片序列组装（PRD 05 §5，技术方案 02 §9.2）。
 *
 * <p>流程：
 * <pre>
 * 1. 解析公式 → AST（保存期已校验，此处只做容错）
 * 2. 取时间桶边界
 * 3. 逐桶读取公式引用的各统计项
 * 4. 逐桶调用 CardEvaluator：缺数入缺、除零不可计算、否则求值
 * </pre>
 *
 * <p>求值**逐桶独立**，因此结构上不存在「沿用上一点」的可能。
 * 输入缺数被判为缺口而非 0（PRD 05 §5）。
 */
@Service
@NamedInterface("dashboard")
public class CardSeriesService {

    private final CardInputSource inputs;

    private final CardEvaluator evaluator;

    private final FormulaParser parser;

    public CardSeriesService(CardInputSource inputs) {
        this.inputs = inputs;
        this.evaluator = new CardEvaluator();
        this.parser = new FormulaParser();
    }

    /**
     * 计算卡片序列。
     *
     * @return 逐桶结果，按时间升序
     */
    public List<CardPoint> series(Card card, Instant from, Instant to, long bucketSeconds) {
        FormulaParser.ParseOutcome parsed = parser.parse(card.getFormula());
        if (!parsed.valid()) {
            return Lists.newArrayList();
        }
        Formula formula = parsed.getFormula();

        List<long[]> boundaries = inputs.bucketBoundaries(from, to, bucketSeconds);
        Map<Long, Map<Stat, BigDecimal>> values = inputs.buckets(card, from, to, bucketSeconds);

        List<CardPoint> points = new ArrayList<>(boundaries.size());
        for (long[] boundary : boundaries) {
            long start = boundary[0];
            long end = boundary[1];
            Map<Stat, BigDecimal> bucketValues = values.getOrDefault(start, Maps.newHashMap());
            points.add(evaluator.evaluate(formula, bucketValues, start, end));
        }
        return List.copyOf(points);
    }

    /**
     * 转成对外契约结构（技术方案 03-api-contract.md §5）。
     */
    public Map<String, Object> describe(Card card, List<CardPoint> points, String unit) {
        List<Map<String, Object>> rendered = new ArrayList<>(points.size());
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<Map<String, Object>> undefined = new ArrayList<>();

        for (CardPoint point : points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucketStart", point.getBucketStart());
            item.put("bucketEnd", point.getBucketEnd());
            item.put("value", point.getValue());
            item.put("outcome", point.getOutcome().name());
            rendered.add(item);

            if (point.isGap()) {
                gaps.add(Map.of("bucketStart", point.getBucketStart(), "missingInputs", point.getMissingInputs()));
            }
            if (point.isUndefined()) {
                // 除零与缺口是不同语义，分开表达（PRD 05 §5）
                undefined.add(Map.of("bucketStart", point.getBucketStart(), "reason", "DIVIDE_BY_ZERO"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardId", card.getId());
        result.put("formula", card.getFormula());
        result.put("unit", unit);
        result.put("thresholdLines", ListUtils.emptyIfNull(card.getThresholdLines()));
        result.put("points", rendered);
        result.put("gaps", gaps);
        result.put("isUndefined", undefined);
        return result;
    }
}
