package com.neocat.dashboard.domain.card;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.collections4.ListUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import com.neocat.dashboard.domain.card.result.CardDimensionView;
import com.neocat.dashboard.domain.card.result.MachineSeries;

/**
 * 卡片维度下钻（PRD 05 §6、§7，技术方案 02 §9.3）。
 */
@Service
@NamedInterface("dashboard")
public class CardDimensionService {

    /**
     * 全机器聚合行的实例标识，下钻时必须排除。
     */
    public static final String AGGREGATE_INSTANCE = "all";

    public CardDimensionView view(Card card, CardDrillRequest request,
                                  List<CardPoint> aggregated,
                                  Map<String, List<CardPoint>> byInstance) {
        if (Objects.isNull(card) || Objects.isNull(request)) {
            throw new IllegalArgumentException("card 与 request 不能为空");
        }
        List<CardPoint> aggregate = CollectionUtils.isEmpty(aggregated) ? Lists.newArrayList() : List.copyOf(aggregated);
        List<ThresholdLine> lines = ListUtils.emptyIfNull(request.getThresholdLines());

        // 聚合模式：不返回任何机器明细
        if (request.aggregateMode() && request.getTopN() <= 0) {
            return new CardDimensionView(aggregate, Lists.newArrayList(), false, lines);
        }

        List<MachineSeries> series = selectSeries(request, byInstance);
        boolean drilled = CollectionUtils.isNotEmpty(series);
        return new CardDimensionView(aggregate, series, drilled, lines);
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 选择要返回的机器序列。
     *
     * <p>两种模式：
     * <ul>
     *   <li>勾选模式（{@code instances} 非空）：只返回被勾选的机器；</li>
     *   <li>Top N 模式（{@code instances} 为空但 {@code topN > 0}）：
     *       按首个非空点的值降序取前 N。</li>
     * </ul>
     * 无论哪种模式都排除全机器聚合行。
     */
    private List<MachineSeries> selectSeries(CardDrillRequest request,
                                                               Map<String, List<CardPoint>> byInstance) {
        if (MapUtils.isEmpty(byInstance)) {
            return Lists.newArrayList();
        }
        List<MachineSeries> candidates = new ArrayList<>();
        byInstance.forEach((instance, points) -> {
            if (Objects.isNull(instance) || Objects.equals(AGGREGATE_INSTANCE, instance)) {
                return;
            }
            candidates.add(new MachineSeries(instance,
                    CollectionUtils.isEmpty(points) ? Lists.newArrayList() : List.copyOf(points)));
        });

        if (!request.aggregateMode()) {
            // 勾选模式：只保留被勾选的机器，顺序遵循请求顺序
            List<MachineSeries> picked = new ArrayList<>();
            for (String wanted : request.getInstances()) {
                candidates.stream()
                        .filter(s -> Objects.equals(s.getInstance(), wanted))
                        .findFirst()
                        .ifPresent(picked::add);
            }
            return picked;
        }

        // Top N 模式：按贡献值降序
        candidates.sort(Comparator
                .comparing((MachineSeries s) -> firstValue(s),
                        Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder())).reversed()
                .thenComparing(MachineSeries::getInstance));
        int limit = Math.max(0, request.getTopN());
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<MachineSeries> limited = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            limited.add(candidates.get(index));
        }
        return limited;
    }

    /**
     * 首个可用值，用作 Top N 排序依据。
     */
    private BigDecimal firstValue(MachineSeries series) {
        for (CardPoint point : series.getPoints()) {
            if (Objects.nonNull(point.getValue())) {
                return point.getValue();
            }
        }
        return null;
    }
}
