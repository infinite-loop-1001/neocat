package com.neocat.common.time.range;

import org.springframework.modulith.NamedInterface;

/**
 * 查询时间范围描述。
 * 固定周期（HOUR / DAY / WEEK / MONTH）、快捷范围、或显式 from/to。
 */
@NamedInterface("time")
public sealed interface RangeSpec permits Hour, Day, Week, Month, QuickRange, Explicit {

}
