package com.neocat.dashboard.domain.card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 卡片维度下钻（PRD 05 §6、§7，技术方案 02 §9.3）。
 */
@org.springframework.stereotype.Service
@org.springframework.modulith.NamedInterface("dashboard")
public class CardDimensionService {

    /** 全机器聚合行的实例标识，下钻时必须排除。 */
    public static final String AGGREGATE_INSTANCE = "all";

    public CardDimensionView view(Card card, CardDrillRequest request,
                                  List<CardPoint> aggregated,
                                  Map<String, List<CardPoint>> byInstance) {
        if (Objects.isNull(card) || Objects.isNull(request)) {
            throw new IllegalArgumentException("card 与 request 不能为空");
        }
        List<CardPoint> aggregate = Objects.isNull(aggregated) ? List.of() : List.copyOf(aggregated);
        List<ThresholdLine> lines = Objects.isNull(request.getThresholdLines()) ? List.of() : request.getThresholdLines();

        // 聚合模式：不返回任何机器明细
        if (request.aggregateMode() && request.getTopN() <= 0) {
            return new CardDimensionView(aggregate, List.of(), false, lines);
        }

        List<CardDimensionView.MachineSeries> series = selectSeries(request, byInstance);
        boolean drilled = !series.isEmpty();
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
    private List<CardDimensionView.MachineSeries> selectSeries(CardDrillRequest request,
                                                              Map<String, List<CardPoint>> byInstance) {
        if (Objects.isNull(byInstance) || byInstance.isEmpty()) {
            return List.of();
        }
        List<CardDimensionView.MachineSeries> candidates = new ArrayList<>();
        byInstance.forEach((instance, points) -> {
            if (Objects.isNull(instance) || AGGREGATE_INSTANCE.equals(instance)) {
                return;
            }
            candidates.add(new CardDimensionView.MachineSeries(instance,
                    Objects.isNull(points) ? List.of() : List.copyOf(points)));
        });

        if (!request.aggregateMode()) {
            // 勾选模式：只保留被勾选的机器，顺序遵循请求顺序
            List<CardDimensionView.MachineSeries> picked = new ArrayList<>();
            for (String wanted : request.getInstances()) {
                candidates.stream()
                        .filter(s -> s.getInstance().equals(wanted))
                        .findFirst()
                        .ifPresent(picked::add);
            }
            return picked;
        }

        // Top N 模式：按贡献值降序
        candidates.sort(Comparator
                .comparingDouble((CardDimensionView.MachineSeries s) -> firstValue(s)).reversed()
                .thenComparing(CardDimensionView.MachineSeries::getInstance));
        int limit = Math.max(0, request.getTopN());
        return candidates.size() <= limit ? candidates : candidates.subList(0, limit);
    }
    /** 首个可用值，用作 Top N 排序依据。 */
    private double firstValue(CardDimensionView.MachineSeries series) {
        for (CardPoint point : series.getPoints()) {
            if (Objects.nonNull(point.getValue())) {
                return point.getValue();
            }
        }
        return Double.NEGATIVE_INFINITY;
    }
}
