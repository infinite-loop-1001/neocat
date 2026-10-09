package com.neocat.dashboard.api.http.convert;

import com.neocat.dashboard.domain.card.*;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 只在 Map 边界做类型收窄，普通字段与集合映射由 MapStruct 生成。
 */
@Getter
@AllArgsConstructor
public class SeriesModel {
    private final long cardId;

    private final String formula;

    private final String unit;

    private final List<ThresholdLine> thresholdLines;

    private final List<Map<String, Object>> points;

    private final List<Map<String, Object>> gaps;

    private final List<Map<String, Object>> isUndefined;
}
