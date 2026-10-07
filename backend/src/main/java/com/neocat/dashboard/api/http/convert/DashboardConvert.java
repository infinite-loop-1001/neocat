package com.neocat.dashboard.api.http.convert;

import com.neocat.dashboard.api.http.dto.DashboardDtos.*;
import com.neocat.dashboard.domain.card.*;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.formula.FormulaParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DashboardConvert {
    private DashboardConvert() {
    }

    public static DashboardResponse dashboard(Dashboard dashboard) {
        return new DashboardResponse(dashboard.getId(), dashboard.getOrgId(), dashboard.getName());
    }

    public static Card card(CardDraft draft, long dashboardId) {
        // 保留原入口行为：阈值线暂不参与保存或告警，不能借规范重构改变产品语义。
        return Card.withoutThresholds(0, dashboardId, draft.getService(), draft.getTargetKind(), draft.getTargetType(),
                draft.getTargetName(), draft.getMetricLabels(), List.of(), draft.getFormula(),
                Objects.isNull(draft.getTimeRange()) ? "RECENT_24H" : draft.getTimeRange(), 0);
    }

    public static CardResponse card(Card card) {
        var parsed = new FormulaParser().parse(card.getFormula());
        return new CardResponse(card.getId(), card.getDashboardId(), card.getService(), card.getTargetKind(),
                card.getTargetType(), card.getTargetName(), card.getMetricLabels(), card.getFormula(), card.getTimeRange(),
                thresholds(card.getThresholdLines()), parsed.valid() ? parsed.getFormula().unit().name() : "NUMBER");
    }

    public static TargetResponse target(AlertableTarget target) {
        return new TargetResponse(target.getKind().name(), target.getCardId(), target.getService(), target.getTargetKind(),
                target.getTargetType(), target.getTargetName(), target.getStats().stream().map(Enum::name).toList());
    }

    private static List<Threshold> thresholds(List<ThresholdLine> lines) {
        return Objects.isNull(lines) ? List.of() : lines.stream()
                .map(line -> new Threshold(line.getDirection().name(), line.getValue())).toList();
    }

    /** 现有卡片应用服务的读模型只在此边界转换，不让 Map 穿过 HTTP 出口。 */
    @SuppressWarnings("unchecked")
    public static SeriesResponse series(Map<String, Object> model) {
        List<Map<String, Object>> points = (List<Map<String, Object>>) model.get("points");
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) model.get("gaps");
        List<Map<String, Object>> undefined = (List<Map<String, Object>>) model.get("undefined");
        return new SeriesResponse(((Number) model.get("cardId")).longValue(), (String) model.get("formula"),
                (String) model.get("unit"), thresholds((List<ThresholdLine>) model.get("thresholdLines")),
                points.stream().map(p -> new SeriesPoint(((Number) p.get("bucketStart")).longValue(),
                        ((Number) p.get("bucketEnd")).longValue(), (Double) p.get("value"), (String) p.get("outcome"))).toList(),
                gaps.stream().map(p -> new Gap(((Number) p.get("bucketStart")).longValue(),
                        (List<String>) p.get("missingInputs"))).toList(),
                undefined.stream().map(p -> new Undefined(((Number) p.get("bucketStart")).longValue(),
                        (String) p.get("reason"))).toList());
    }
}
