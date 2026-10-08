package com.neocat.dashboard.api.http.convert;

import com.google.common.collect.Lists;
import com.neocat.dashboard.api.http.dto.DashboardDtos.*;
import com.neocat.dashboard.domain.card.*;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.formula.FormulaParser;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.ListUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.IterableMapping;
import org.mapstruct.NullValueMappingStrategy;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface DashboardConvert {

    DashboardResponse dashboard(Dashboard dashboard);

    default Card card(CardDraft draft, long dashboardId) {
        // 保留原入口行为：阈值线暂不参与保存或告警，不能借规范重构改变产品语义。
        return Card.withoutThresholds(0, dashboardId, draft.getService(), draft.getTargetKind(), draft.getTargetType(),
                draft.getTargetName(), draft.getMetricLabels(), Lists.newArrayList(), draft.getFormula(),
                Objects.isNull(draft.getTimeRange()) ? "RECENT_24H" : draft.getTimeRange(), 0);
    }

    @Mapping(target = "unit", expression = "java(formulaUnit(card))")
    CardResponse card(Card card);

    TargetResponse target(AlertableTarget target);

    Threshold threshold(ThresholdLine line);

    @IterableMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
    List<Threshold> thresholds(List<ThresholdLine> lines);

    default String formulaUnit(Card card) {
        var parsed = new FormulaParser().parse(card.getFormula());
        return parsed.valid() ? parsed.getFormula().unit().name() : "NUMBER";
    }

    /**
     * 现有卡片应用服务的读模型只在此边界转换，不让 Map 穿过 HTTP 出口。
     */
    @SuppressWarnings("unchecked")
    default SeriesResponse series(Map<String, Object> model) {
        List<Map<String, Object>> points = (List<Map<String, Object>>) model.get("points");
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) model.get("gaps");
        List<Map<String, Object>> undefined = (List<Map<String, Object>>) model.get("isUndefined");
        return series(new SeriesModel(((Number) model.get("cardId")).longValue(), (String) model.get("formula"),
                (String) model.get("unit"), ListUtils.emptyIfNull((List<ThresholdLine>) model.get("thresholdLines")),
                points, gaps, undefined));
    }

    SeriesResponse series(SeriesModel model);

    @Mapping(target = "bucketStart", source = "bucketStart", qualifiedByName = "mapNumber")
    @Mapping(target = "bucketEnd", source = "bucketEnd", qualifiedByName = "mapNumber")
    @Mapping(target = "value", source = "value", qualifiedByName = "mapDecimal")
    @Mapping(target = "outcome", source = "outcome", qualifiedByName = "mapText")
    SeriesPoint point(Map<String, Object> point);

    @Mapping(target = "bucketStart", source = "bucketStart", qualifiedByName = "mapNumber")
    @Mapping(target = "missingInputs", source = "missingInputs", qualifiedByName = "mapMissingInputs")
    Gap gap(Map<String, Object> gap);

    @Mapping(target = "bucketStart", source = "bucketStart", qualifiedByName = "mapNumber")
    @Mapping(target = "reason", source = "reason", qualifiedByName = "mapText")
    Undefined undefined(Map<String, Object> undefined);

    @Named("mapNumber")
    default long number(Object value) {
        return ((Number) value).longValue();
    }

    @Named("mapText")
    default String text(Object value) {
        return (String) value;
    }

    @Named("mapDecimal")
    default BigDecimal decimal(Object value) {
        return (BigDecimal) value;
    }

    @SuppressWarnings("unchecked")
    @Named("mapMissingInputs")
    default List<String> missingInputs(Object value) {
        return (List<String>) value;
    }

    /**
     * 只在 Map 边界做类型收窄，普通字段与集合映射由 MapStruct 生成。
     */
    @Getter
    @AllArgsConstructor
    class SeriesModel {
        private final long cardId;

        private final String formula;

        private final String unit;

        private final List<ThresholdLine> thresholdLines;

        private final List<Map<String, Object>> points;

        private final List<Map<String, Object>> gaps;

        private final List<Map<String, Object>> isUndefined;
    }
}
